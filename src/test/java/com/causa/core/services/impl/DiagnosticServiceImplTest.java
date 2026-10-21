package com.causa.core.services.impl;

import com.causa.common.constants.AlertConstants.AlertSeverity;
import com.causa.common.constants.AlertConstants.AlertStatus;
import com.causa.common.constants.DiagnosticConstants.DiagnosticStatus;
import com.causa.config.RcaConfig;
import com.causa.core.domain.Alert;
import com.causa.core.domain.Diagnostic;
import com.causa.core.domain.DiagnosticContext;
import com.causa.core.domain.PageRequest;
import com.causa.core.domain.PageResult;
import com.causa.core.ports.AlertRepository;
import com.causa.core.ports.DiagnosticRepository;
import com.causa.core.services.RcaPromptBuilder;
import com.causa.mcp.McpClient;
import com.causa.mcp.config.McpSettings;
import com.causa.mcp.config.RcaProfile;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * Unit tests for {@link DiagnosticServiceImpl}.
 *
 * <p>The full async pipeline (MCP context + LLM) runs on a background thread and is
 * not tested here (it requires integration testing). These unit tests verify:
 * <ul>
 *   <li>{@link DiagnosticService#triggerDiagnostics} — creates a PENDING diagnostic, persists it, and returns immediately</li>
 *   <li>{@link DiagnosticService#listDiagnostics} — delegates to repository</li>
 *   <li>{@link DiagnosticService#getDiagnosticById} — delegates to repository</li>
 * </ul>
 *
 * @since 0.0.1
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("DiagnosticServiceImpl Tests")
class DiagnosticServiceImplTest {

    @Mock
    private DiagnosticRepository diagnosticRepository;

    @Mock
    private AlertRepository alertRepository;

    @Mock
    private com.causa.mcp.McpRegistry mcpRegistry;

    @Mock
    private RcaPromptBuilder rcaPromptBuilder;

    @Mock
    private com.causa.core.ports.llm.PromptSender promptSender;

    @Mock
    private com.causa.config.LlmConfigCache llmConfigCache;

    @Mock
    private com.fasterxml.jackson.databind.ObjectMapper objectMapper;

    @Mock
    private jakarta.validation.Validator validator;

    @Mock
    private RcaConfig rcaConfig;

    @Mock
    private jakarta.enterprise.inject.Instance<com.causa.core.services.validation.RcaValidator> rcaValidatorInstance;

    private DiagnosticServiceImpl diagnosticService;

    @BeforeEach
    void setUp() {
        org.mockito.Mockito.when(rcaValidatorInstance.isResolvable()).thenReturn(false);
        diagnosticService = new DiagnosticServiceImpl(
                diagnosticRepository,
                alertRepository,
                mcpRegistry,
                rcaPromptBuilder,
                promptSender,
                llmConfigCache,
                objectMapper,
                validator,
                rcaConfig,
                rcaValidatorInstance
        );
        lenient().when(rcaConfig.maxParallelProfiles()).thenReturn(2);
        lenient().when(diagnosticRepository.update(any(Diagnostic.class))).thenAnswer(inv -> inv.getArgument(0));
    }

    private static Alert buildAlert(String id) {
        return Alert.builder()
                .alertId(id)
                .alertName("TestAlert")
                .severity(AlertSeverity.CRITICAL)
                .status(AlertStatus.ACCEPTED)
                .workloadInfo(Alert.WorkloadInfo.of("pod-1", "container-1", "default", "cluster-1", "Deployment"))
                .workloadName("container-1")
                .build();
    }

    private static Diagnostic buildDiagnostic(String id, String alertId, DiagnosticStatus status) {
        return Diagnostic.builder()
                .diagnosticId(id)
                .alertId(alertId)
                .status(status)
                .generatedAt(Instant.now())
                .build();
    }

    // -------------------------------------------------------------------------
    // triggerDiagnostics
    // -------------------------------------------------------------------------

    @Nested
    @DisplayName("triggerDiagnostics Tests")
    class TriggerDiagnosticsTests {

        @Test
        @DisplayName("Should return PENDING diagnostic immediately")
        void shouldReturnPendingDiagnosticImmediately() {
            Alert alert = buildAlert("alert-1");
            when(diagnosticRepository.save(any(Diagnostic.class))).thenAnswer(inv -> inv.getArgument(0));

            Diagnostic result = diagnosticService.triggerDiagnostics(alert);

            assertNotNull(result);
            assertEquals(DiagnosticStatus.PENDING, result.getStatus());
            assertEquals("alert-1", result.getAlertId());
        }

        @Test
        @DisplayName("Should generate a unique diagnostic ID")
        void shouldGenerateUniqueDiagnosticId() {
            Alert alert = buildAlert("alert-1");
            when(diagnosticRepository.save(any(Diagnostic.class))).thenAnswer(inv -> inv.getArgument(0));

            Diagnostic result = diagnosticService.triggerDiagnostics(alert);

            assertNotNull(result.getDiagnosticId());
            assertFalse(result.getDiagnosticId().isBlank());
        }

        @Test
        @DisplayName("Should persist the PENDING diagnostic stub")
        void shouldPersistPendingDiagnosticStub() {
            Alert alert = buildAlert("alert-1");
            when(diagnosticRepository.save(any(Diagnostic.class))).thenAnswer(inv -> inv.getArgument(0));

            diagnosticService.triggerDiagnostics(alert);

            verify(diagnosticRepository).save(any(Diagnostic.class));
        }

        @Test
        @DisplayName("Should return before pipeline completes (non-blocking)")
        void shouldReturnImmediately() {
            Alert alert = buildAlert("alert-1");
            when(diagnosticRepository.save(any(Diagnostic.class))).thenAnswer(inv -> inv.getArgument(0));

            // Should complete without waiting for background pipeline
            long start = System.currentTimeMillis();
            Diagnostic result = diagnosticService.triggerDiagnostics(alert);
            long elapsed = System.currentTimeMillis() - start;

            assertNotNull(result);
            assertTrue(elapsed < 5000, "triggerDiagnostics should return immediately (< 5s), took: " + elapsed + "ms");
        }

        @Test
        @DisplayName("Should generate different IDs for different alerts")
        void shouldGenerateDifferentIdsForDifferentAlerts() {
            Alert alert1 = buildAlert("alert-1");
            Alert alert2 = buildAlert("alert-2");
            when(diagnosticRepository.save(any(Diagnostic.class))).thenAnswer(inv -> inv.getArgument(0));

            Diagnostic d1 = diagnosticService.triggerDiagnostics(alert1);
            Diagnostic d2 = diagnosticService.triggerDiagnostics(alert2);

            assertNotEquals(d1.getDiagnosticId(), d2.getDiagnosticId());
        }

        @Test
        @DisplayName("Should set generatedAt to approximately now")
        void shouldSetGeneratedAtToNow() {
            Alert alert = buildAlert("alert-1");
            when(diagnosticRepository.save(any(Diagnostic.class))).thenAnswer(inv -> inv.getArgument(0));
            Instant before = Instant.now();

            Diagnostic result = diagnosticService.triggerDiagnostics(alert);

            Instant after = Instant.now();
            assertNotNull(result.getGeneratedAt());
            assertTrue(result.getGeneratedAt().isAfter(before.minusSeconds(1)));
            assertTrue(result.getGeneratedAt().isBefore(after.plusSeconds(1)));
        }
    }

    // -------------------------------------------------------------------------
    // listDiagnostics
    // -------------------------------------------------------------------------

    @Nested
    @DisplayName("listDiagnostics Tests")
    class ListDiagnosticsTests {

        @Test
        @DisplayName("Should delegate to repository and return paginated result")
        void shouldDelegateToRepository() {
            Diagnostic d1 = buildDiagnostic("d1", "a1", DiagnosticStatus.COMPLETED);
            Diagnostic d2 = buildDiagnostic("d2", "a2", DiagnosticStatus.PENDING);
            PageResult<Diagnostic> page = PageResult.of(List.of(d1, d2), 2L, PageRequest.of(1, 0));
            when(diagnosticRepository.search(any(), any())).thenReturn(page);

            PageResult<Diagnostic> result = diagnosticService.listDiagnostics(Diagnostic.Filter.empty(), PageRequest.of(1, 0));

            assertEquals(2, result.items().size());
            assertEquals(2L, result.total());
            verify(diagnosticRepository).search(any(), any());
        }

        @Test
        @DisplayName("Should return empty page when no diagnostics")
        void shouldReturnEmptyListWhenNoDiagnostics() {
            PageResult<Diagnostic> page = PageResult.of(List.of(), 0L, PageRequest.of(1, 0));
            when(diagnosticRepository.search(any(), any())).thenReturn(page);

            PageResult<Diagnostic> result = diagnosticService.listDiagnostics(Diagnostic.Filter.empty(), PageRequest.of(1, 0));

            assertTrue(result.items().isEmpty());
            assertEquals(0L, result.total());
        }
    }

    // -------------------------------------------------------------------------
    // getDiagnosticById
    // -------------------------------------------------------------------------

    @Nested
    @DisplayName("getDiagnosticById Tests")
    class GetDiagnosticByIdTests {

        @Test
        @DisplayName("Should return diagnostic when found")
        void shouldReturnDiagnosticWhenFound() {
            Diagnostic d = buildDiagnostic("diag-xyz", "alert-xyz", DiagnosticStatus.COMPLETED);
            when(diagnosticRepository.findById("diag-xyz")).thenReturn(Optional.of(d));

            Optional<Diagnostic> result = diagnosticService.getDiagnosticById("diag-xyz");

            assertTrue(result.isPresent());
            assertEquals("diag-xyz", result.get().getDiagnosticId());
            verify(diagnosticRepository).findById("diag-xyz");
        }

        @Test
        @DisplayName("Should return empty when diagnostic not found")
        void shouldReturnEmptyWhenNotFound() {
            when(diagnosticRepository.findById("missing")).thenReturn(Optional.empty());

            Optional<Diagnostic> result = diagnosticService.getDiagnosticById("missing");

            assertTrue(result.isEmpty());
        }
    }

    @Nested
    @DisplayName("multi-profile pipeline")
    class MultiProfileTests {

        @Test
        @DisplayName("one alert creates two diagnostics that share a suffix and collects MCP once")
        void twoProfilesShareSuffixAndCollectOnce() {
            Alert alert = buildAlert("alert-1");
            RcaProfile cryo = new RcaProfile("cryo", "cryo_diag", null, List.of("*"), List.of(), List.of(), false);
            RcaProfile baseline = new RcaProfile("default", "diag", null, List.of("*"), List.of("cryostat"), List.of(), false);
            when(mcpRegistry.profiles()).thenReturn(List.of(cryo, baseline));
            when(mcpRegistry.allClients()).thenReturn(List.of(client("kubernetes"), client("kruize"), client("cryostat")));
            when(mcpRegistry.collectContext(eq(alert), any())).thenReturn(contextWithCryostat());
            when(rcaPromptBuilder.getSystemPrompt()).thenReturn("system");
            when(rcaPromptBuilder.buildPrompt(eq(alert), any(), any())).thenReturn("prompt");
            when(promptSender.send(any())).thenThrow(new RuntimeException("stop after prompt"));
            when(diagnosticRepository.save(any(Diagnostic.class))).thenAnswer(inv -> inv.getArgument(0));

            List<Diagnostic> created = diagnosticService.triggerDiagnosticsForProfiles(alert);

            assertEquals(2, created.size());
            assertEquals(created.get(0).getRunGroupId(), created.get(1).getRunGroupId());
            assertTrue(created.stream().anyMatch(d -> d.getDiagnosticId().equals("cryo_diag_" + d.getRunGroupId())
                    && "cryo".equals(d.getProfileId())));
            assertTrue(created.stream().anyMatch(d -> d.getDiagnosticId().equals("diag_" + d.getRunGroupId())
                    && "default".equals(d.getProfileId())));

            ArgumentCaptor<String> contexts = ArgumentCaptor.forClass(String.class);
            verify(rcaPromptBuilder, timeout(5000).times(2)).buildPrompt(eq(alert), contexts.capture(), any());
            verify(mcpRegistry, timeout(5000).times(1)).collectContext(eq(alert), eq(Set.of("kubernetes", "kruize", "cryostat")));
            assertTrue(contexts.getAllValues().stream().anyMatch(text -> text.contains("CRYOSTAT_ANALYSIS")));
            assertTrue(contexts.getAllValues().stream().anyMatch(text -> !text.contains("CRYOSTAT_")));
            verify(alertRepository, timeout(5000).times(1))
                    .updateProcessingStatus(eq("alert-1"), anyString());
        }

        @Test
        @DisplayName("skipIfRequiredMissing does not call the LLM")
        void skipsWhenRequiredServerHasNoData() {
            Alert alert = buildAlert("alert-1");
            RcaProfile cryo = new RcaProfile(
                    "cryo", "cryo_diag", null, List.of("*"), List.of(), List.of("cryostat"), true);
            when(mcpRegistry.profiles()).thenReturn(List.of(cryo));
            when(mcpRegistry.allClients()).thenReturn(List.of(client("kubernetes")));
            when(mcpRegistry.collectContext(eq(alert), any())).thenReturn(DiagnosticContext.builder()
                    .put("kubernetes", "POD_STATUS", "Running")
                    .build());
            when(diagnosticRepository.save(any(Diagnostic.class))).thenAnswer(inv -> inv.getArgument(0));

            diagnosticService.triggerDiagnosticsForProfiles(alert);

            ArgumentCaptor<Diagnostic> updates = ArgumentCaptor.forClass(Diagnostic.class);
            verify(diagnosticRepository, timeout(5000).atLeastOnce()).update(updates.capture());
            verify(alertRepository, timeout(5000)).updateProcessingStatus(eq("alert-1"), anyString());
            assertTrue(updates.getAllValues().stream().anyMatch(d ->
                    d.getStatus() == DiagnosticStatus.SKIPPED
                            && d.getRemarks() != null
                            && d.getRemarks().contains("cryostat")));
            verify(rcaPromptBuilder, never()).buildPrompt(any(), any(), any());
        }

        @Test
        @DisplayName("one profile failing still runs the other")
        void oneProfileFailureDoesNotStopTheOther() {
            Alert alert = buildAlert("alert-1");
            RcaProfile cryo = new RcaProfile("cryo", "cryo_diag", null, List.of("*"), List.of(), List.of(), false);
            RcaProfile baseline = new RcaProfile("default", "diag", null, List.of("*"), List.of("cryostat"), List.of(), false);
            when(mcpRegistry.profiles()).thenReturn(List.of(cryo, baseline));
            when(mcpRegistry.allClients()).thenReturn(List.of(client("kubernetes"), client("cryostat")));
            when(mcpRegistry.collectContext(eq(alert), any())).thenReturn(contextWithCryostat());
            when(rcaPromptBuilder.getSystemPrompt()).thenReturn("system");
            when(rcaPromptBuilder.buildPrompt(eq(alert), any(), any())).thenAnswer(inv -> {
                String text = inv.getArgument(1);
                if (text.contains("CRYOSTAT_ANALYSIS")) {
                    throw new RuntimeException("cryo failed");
                }
                return "prompt";
            });
            when(promptSender.send(any())).thenThrow(new RuntimeException("baseline llm failed"));
            when(diagnosticRepository.save(any(Diagnostic.class))).thenAnswer(inv -> inv.getArgument(0));

            diagnosticService.triggerDiagnosticsForProfiles(alert);

            verify(rcaPromptBuilder, timeout(5000).times(2)).buildPrompt(eq(alert), any(), any());
            ArgumentCaptor<Diagnostic> updates = ArgumentCaptor.forClass(Diagnostic.class);
            verify(diagnosticRepository, timeout(5000).atLeast(2)).update(updates.capture());
            assertEquals(2, updates.getAllValues().stream()
                    .filter(d -> d.getStatus() == DiagnosticStatus.FAILED)
                    .map(Diagnostic::getProfileId)
                    .distinct()
                    .count());
            verify(alertRepository, timeout(5000).times(1)).updateProcessingStatus(eq("alert-1"), anyString());
        }

        private static DiagnosticContext contextWithCryostat() {
            return DiagnosticContext.builder()
                    .podName("pod-1")
                    .put("kubernetes", "POD_STATUS", "Running")
                    .put("kruize", "KRUIZE_PERFORMANCE_RECOMMENDATIONS", "tune")
                    .put("cryostat", "CRYOSTAT_DISCOVERY", "pod found")
                    .put("cryostat", "CRYOSTAT_ANALYSIS", "hot allocation")
                    .build();
        }

        private static McpClient client(String name) {
            return new McpClient(name, new McpSettings.ServerConfig(
                    "streamable-http",
                    "http://127.0.0.1/mcp",
                    Map.of(),
                    true,
                    new McpSettings.HealthCheckConfig("http://127.0.0.1/healthz", 1000),
                    1000,
                    Map.of(),
                    null,
                    List.of()));
        }
    }
}
