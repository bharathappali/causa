package com.causa.mcp;

import com.causa.common.constants.AlertConstants.AlertSeverity;
import com.causa.common.constants.AlertConstants.AlertStatus;
import com.causa.core.domain.Alert;
import com.causa.mcp.config.McpSettings;
import com.causa.mcp.config.RcaProfile;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link McpRegistry}.
 *
 * @since 0.0.1
 */
@DisplayName("McpRegistry Tests")
class McpRegistryTest {

    private static McpSettings.ServerConfig serverConfig() {
        return new McpSettings.ServerConfig(
                "streamable-http",
                "http://example:8080/mcp",
                Map.of(),
                false,
                new McpSettings.HealthCheckConfig("http://example:8080/healthz", 5000),
                5000,
                Map.of(),
                null,
                List.of());
    }

    @Test
    @DisplayName("init() populates one client per configured server")
    void initPopulatesClients() {
        McpRegistry registry = new McpRegistry(null);
        McpSettings settings = new McpSettings(Map.of(
                "kubernetes", serverConfig(),
                "kruize", serverConfig()));

        registry.init(settings);

        assertTrue(registry.isInitialized());
        assertTrue(registry.getInitializationError().isEmpty());
        assertEquals(2, registry.allClients().size());
        assertTrue(registry.getClient("kubernetes").isPresent());
        assertEquals("kubernetes", registry.getClient("kubernetes").get().getServerName());
        assertTrue(registry.getClient("does-not-exist").isEmpty());
    }

    @Test
    @DisplayName("markInitFailed() clears clients and records the failure reason")
    void markInitFailedClearsState() {
        McpRegistry registry = new McpRegistry(null);
        registry.init(new McpSettings(Map.of("kubernetes", serverConfig())));
        assertTrue(registry.isInitialized());

        registry.markInitFailed("mcp.json not found");

        assertFalse(registry.isInitialized());
        assertTrue(registry.allClients().isEmpty());
        assertEquals(Optional.of("mcp.json not found"), registry.getInitializationError());
    }

    @Test
    @DisplayName("A fresh registry starts uninitialized with no clients")
    void freshRegistryIsUninitialized() {
        McpRegistry registry = new McpRegistry(null);

        assertFalse(registry.isInitialized());
        assertTrue(registry.allClients().isEmpty());
        assertTrue(registry.getInitializationError().isEmpty());
    }

    @Test
    @DisplayName("profiles() returns the implicit default when none are configured")
    void implicitDefaultProfile() {
        McpRegistry registry = new McpRegistry(null);
        registry.init(new McpSettings(Map.of("kubernetes", serverConfig())));

        assertEquals(1, registry.profiles().size());
        RcaProfile profile = registry.profiles().get(0);
        assertEquals("default", profile.id());
        assertEquals("diag", profile.reportPrefix());
        assertEquals(List.of("*"), profile.includeServers());
        assertEquals(Set.of("kubernetes"), profile.resolveServers(Set.of("kubernetes")));
    }

    @Test
    @DisplayName("profiles() returns the configured profiles when present")
    void configuredProfiles() {
        McpRegistry registry = new McpRegistry(null);
        RcaProfile cryo = new RcaProfile("cryo", "cryo_diag", null, List.of("*"), List.of(), List.of("cryostat"), false);
        registry.init(new McpSettings(Map.of("kubernetes", serverConfig()), List.of(cryo)));

        assertEquals(List.of(cryo), registry.profiles());
    }

    @Test
    @DisplayName("collectContext calls each server in the union once")
    void collectContextCallsUnionOnce() throws IOException {
        AtomicInteger kubernetesHits = new AtomicInteger();
        AtomicInteger kruizeHits = new AtomicInteger();
        HttpServer http = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        http.createContext("/kubernetes", exchange -> respond(exchange, kubernetesHits));
        http.createContext("/kruize", exchange -> respond(exchange, kruizeHits));
        http.start();
        try {
            int port = http.getAddress().getPort();
            McpRegistry registry = new McpRegistry(null);
            registry.init(new McpSettings(Map.of(
                    "kubernetes", serverConfig("http://127.0.0.1:" + port + "/kubernetes"),
                    "kruize", serverConfig("http://127.0.0.1:" + port + "/kruize"))));

            Set<String> names = Set.of("kubernetes", "kruize");
            RcaProfile cryo = new RcaProfile("cryo", "cryo_diag", null, List.of("*"), List.of(), List.of(), false);
            RcaProfile baseline = new RcaProfile("default", "diag", null, List.of("*"), List.of("kruize"), List.of(), false);
            Set<String> union = new HashSet<>();
            union.addAll(cryo.resolveServers(names));
            union.addAll(baseline.resolveServers(names));

            registry.collectContext(alert(), union);

            assertEquals(Set.of("kubernetes", "kruize"), union);
            assertEquals(1, kubernetesHits.get());
            assertEquals(1, kruizeHits.get());
        } finally {
            http.stop(0);
        }
    }

    @Test
    @DisplayName("collectContext(alert, servers) does not call servers outside the set")
    void collectContextSkipsServersOutsideTheSet() throws IOException {
        AtomicInteger kubernetesHits = new AtomicInteger();
        AtomicInteger kruizeHits = new AtomicInteger();
        HttpServer http = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        http.createContext("/kubernetes", exchange -> respond(exchange, kubernetesHits));
        http.createContext("/kruize", exchange -> respond(exchange, kruizeHits));
        http.start();
        try {
            int port = http.getAddress().getPort();
            McpRegistry registry = new McpRegistry(null);
            registry.init(new McpSettings(Map.of(
                    "kubernetes", serverConfig("http://127.0.0.1:" + port + "/kubernetes"),
                    "kruize", serverConfig("http://127.0.0.1:" + port + "/kruize"))));

            registry.collectContext(alert(), Set.of("kubernetes"));

            assertEquals(1, kubernetesHits.get());
            assertEquals(0, kruizeHits.get());
        } finally {
            http.stop(0);
        }
    }

    private static void respond(com.sun.net.httpserver.HttpExchange exchange, AtomicInteger hits) throws IOException {
        hits.incrementAndGet();
        exchange.sendResponseHeaders(200, -1);
        exchange.close();
    }

    private static Alert alert() {
        return Alert.builder()
                .alertId("alrt_test00000001234")
                .alertName("TestAlert")
                .severity(AlertSeverity.CRITICAL)
                .status(AlertStatus.ACCEPTED)
                .workloadInfo(Alert.WorkloadInfo.of("pod-1", "container-1", "default", "cluster-1", "Deployment"))
                .workloadName("container-1")
                .build();
    }

    private static McpSettings.ServerConfig serverConfig(String healthUrl) {
        return new McpSettings.ServerConfig(
                "streamable-http",
                healthUrl,
                Map.of(),
                true,
                new McpSettings.HealthCheckConfig(healthUrl, 2000),
                2000,
                Map.of(),
                null,
                List.of());
    }
}

