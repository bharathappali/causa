package com.causa.mcp.config;

import com.causa.common.exceptions.McpConfigLoadException;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link McpSettingsLoader}.
 *
 * @since 0.0.1
 */
@DisplayName("McpSettingsLoader Tests")
class McpSettingsLoaderTest {

    @TempDir
    Path tempDir;

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final Validator validator = Validation.buildDefaultValidatorFactory().getValidator();

    private static final String VALID_JSON = """
            {
              "mcpServers": {
                "kubernetes": {
                  "type": "streamable-http",
                  "url": "http://kubernetes-mcp-server:8080/mcp",
                  "headers": {},
                  "optional": false,
                  "alwaysAllow": ["pods_get", "pods_log"],
                  "healthCheck": { "url": "http://kubernetes-mcp-server:8080/healthz", "timeoutMs": 5000 },
                  "timeoutMs": 5000,
                  "description": "Kubernetes MCP",
                  "tools": [
                    { "name": "pods_get", "contextKey": "POD_STATUS", "arguments": { "name": "${podName}" } }
                  ]
                }
              }
            }
            """;

    private McpSettingsLoader loaderFor(String configPath) {
        return new McpSettingsLoader(objectMapper, validator, configPath);
    }

    private Path writeFile(String name, String content) throws IOException {
        Path file = tempDir.resolve(name);
        Files.writeString(file, content, StandardCharsets.UTF_8);
        return file;
    }

    @Test
    @DisplayName("Loads and validates a well-formed mcp.json")
    void loadsValidFile() throws IOException {
        Path file = writeFile("mcp.json", VALID_JSON);

        McpSettings settings = loaderFor(file.toString()).load();

        assertEquals(1, settings.mcpServers().size());
        McpSettings.ServerConfig kubernetes = settings.mcpServers().get("kubernetes");
        assertNotNull(kubernetes);
        assertEquals("http://kubernetes-mcp-server:8080/mcp", kubernetes.url());
        assertFalse(kubernetes.optional());
        assertEquals(5000, kubernetes.healthCheck().timeoutMs());
        assertEquals(1, kubernetes.tools().size());
        assertEquals("POD_STATUS", kubernetes.tools().get(0).contextKey());
        assertTrue(settings.rcaProfiles().isEmpty());
    }

    @Test
    @DisplayName("Parses rcaProfiles and applies list defaults")
    void parsesProfilesAndDefaults() throws IOException {
        String json = """
                {
                  "mcpServers": {
                    "kubernetes": %s,
                    "cryostat": %s
                  },
                  "rcaProfiles": [
                    { "id": "cryo", "reportPrefix": "cryo_diag", "description": "All MCPs incl. Cryostat JFR",
                      "includeServers": ["*"], "requireServers": ["cryostat"], "skipIfRequiredMissing": false },
                    { "id": "default", "reportPrefix": "diag", "description": "Baseline without Cryostat",
                      "includeServers": ["*"], "excludeServers": ["cryostat"] },
                    { "id": "bare", "reportPrefix": "bare" }
                  ]
                }
                """.formatted(server("kubernetes"), server("cryostat"));
        Path file = writeFile("profiles.json", json);

        McpSettings settings = loaderFor(file.toString()).load();

        assertEquals(3, settings.rcaProfiles().size());
        RcaProfile cryo = settings.rcaProfiles().get(0);
        assertEquals("cryo", cryo.id());
        assertEquals("cryo_diag", cryo.reportPrefix());
        assertEquals(Set.of("kubernetes", "cryostat"), cryo.resolveServers(settings.mcpServers().keySet()));
        assertFalse(cryo.skipIfRequiredMissing());

        RcaProfile baseline = settings.rcaProfiles().get(1);
        assertEquals(Set.of("kubernetes"), baseline.resolveServers(settings.mcpServers().keySet()));
        assertTrue(baseline.requireServers().isEmpty());
        assertFalse(baseline.skipIfRequiredMissing());

        RcaProfile bare = settings.rcaProfiles().get(2);
        assertEquals(List.of("*"), bare.includeServers());
        assertTrue(bare.excludeServers().isEmpty());
        assertTrue(bare.requireServers().isEmpty());
        assertFalse(bare.skipIfRequiredMissing());
    }

    @Test
    @DisplayName("Rejects an unknown server named by a profile")
    void rejectsUnknownServer() throws IOException {
        assertProfileRejected(profileJson("""
                { "id": "default", "reportPrefix": "diag", "excludeServers": ["missing"] }
                """), "unknown server 'missing'");
    }

    @Test
    @DisplayName("Rejects a duplicate profile id")
    void rejectsDuplicateId() throws IOException {
        assertProfileRejected(profileJson("""
                { "id": "default", "reportPrefix": "diag" },
                { "id": "default", "reportPrefix": "other" }
                """), "duplicate rcaProfiles id 'default'");
    }

    @Test
    @DisplayName("Rejects a profile id that does not match the pattern")
    void rejectsBadId() throws IOException {
        assertProfileRejected(profileJson("""
                { "id": "Bad_Id", "reportPrefix": "diag" }
                """), "rcaProfiles id 'Bad_Id'");
    }

    @Test
    @DisplayName("Rejects a duplicate report prefix")
    void rejectsDuplicatePrefix() throws IOException {
        assertProfileRejected(profileJson("""
                { "id": "cryo", "reportPrefix": "diag" },
                { "id": "default", "reportPrefix": "diag" }
                """), "duplicate rcaProfiles reportPrefix 'diag'");
    }

    @Test
    @DisplayName("Rejects a report prefix that does not match the pattern")
    void rejectsBadPrefix() throws IOException {
        assertProfileRejected(profileJson("""
                { "id": "default", "reportPrefix": "BadPrefix" }
                """), "reportPrefix 'BadPrefix'");
    }

    @Test
    @DisplayName("Rejects a profile whose resolved server set is empty")
    void rejectsEmptyServerSet() throws IOException {
        assertProfileRejected(profileJson("""
                { "id": "default", "reportPrefix": "diag",
                  "includeServers": ["kubernetes"], "excludeServers": ["kubernetes"] }
                """), "empty server set");
    }

    @Test
    @DisplayName("Rejects requireServers that are not in the resolved set")
    void rejectsRequireOutsideSet() throws IOException {
        String json = """
                {
                  "mcpServers": {
                    "kubernetes": %s,
                    "cryostat": %s
                  },
                  "rcaProfiles": [
                    { "id": "default", "reportPrefix": "diag",
                      "includeServers": ["kubernetes"], "requireServers": ["cryostat"] }
                  ]
                }
                """.formatted(server("kubernetes"), server("cryostat"));
        assertProfileRejected(json, "requireServers 'cryostat' is not in the resolved server set");
    }

    private String server(String name) {
        return """
                {
                  "type": "streamable-http",
                  "url": "http://%s:8080/mcp",
                  "optional": true,
                  "healthCheck": { "url": "http://%s:8080/healthz", "timeoutMs": 5000 }
                }
                """.formatted(name, name);
    }

    private String profileJson(String profiles) {
        return """
                {
                  "mcpServers": { "kubernetes": %s },
                  "rcaProfiles": [ %s ]
                }
                """.formatted(server("kubernetes"), profiles);
    }

    private void assertProfileRejected(String json, String messagePart) throws IOException {
        Path file = writeFile("bad-profiles.json", json);
        McpConfigLoadException ex = assertThrows(McpConfigLoadException.class,
                () -> loaderFor(file.toString()).load());
        assertEquals("ValidationFailed", ex.getErrorType());
        assertTrue(ex.getMessage().contains(messagePart), ex.getMessage());
    }

    @Test
    @DisplayName("Throws McpConfigLoadException when the file is missing")
    void throwsWhenFileMissing() {
        String missingPath = tempDir.resolve("does-not-exist.json").toString();

        McpConfigLoadException ex = assertThrows(McpConfigLoadException.class,
                () -> loaderFor(missingPath).load());
        assertEquals("IOException", ex.getErrorType());
    }

    @Test
    @DisplayName("Throws McpConfigLoadException when the JSON is malformed")
    void throwsWhenJsonMalformed() throws IOException {
        Path file = writeFile("broken.json", "{ not valid json ");

        McpConfigLoadException ex = assertThrows(McpConfigLoadException.class,
                () -> loaderFor(file.toString()).load());
        assertEquals("IOException", ex.getErrorType());
    }

    @Test
    @DisplayName("Throws McpConfigLoadException when the file content is the JSON literal null")
    void throwsWhenJsonIsNullLiteral() throws IOException {
        Path file = writeFile("null.json", "null");

        McpConfigLoadException ex = assertThrows(McpConfigLoadException.class,
                () -> loaderFor(file.toString()).load());
        assertEquals("ValidationFailed", ex.getErrorType());
    }

    @Test
    @DisplayName("Throws McpConfigLoadException when mcpServers is empty")
    void throwsWhenMcpServersEmpty() throws IOException {
        Path file = writeFile("empty.json", "{ \"mcpServers\": {} }");

        McpConfigLoadException ex = assertThrows(McpConfigLoadException.class,
                () -> loaderFor(file.toString()).load());
        assertEquals("ValidationFailed", ex.getErrorType());
        assertTrue(ex.getMessage().contains("mcpServers"));
    }

    @Test
    @DisplayName("Throws McpConfigLoadException when a required field is blank")
    void throwsWhenRequiredFieldBlank() throws IOException {
        String json = """
                {
                  "mcpServers": {
                    "kubernetes": {
                      "type": "streamable-http",
                      "url": "",
                      "optional": false,
                      "alwaysAllow": ["pods_get"],
                      "healthCheck": { "url": "http://k8s:8080/healthz", "timeoutMs": 5000 }
                    }
                  }
                }
                """;
        Path file = writeFile("blank-url.json", json);

        McpConfigLoadException ex = assertThrows(McpConfigLoadException.class,
                () -> loaderFor(file.toString()).load());
        assertEquals("ValidationFailed", ex.getErrorType());
    }
}