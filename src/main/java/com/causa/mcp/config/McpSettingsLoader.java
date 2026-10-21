package com.causa.mcp.config;

import com.causa.common.exceptions.McpConfigLoadException;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validator;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * MCP Settings Loader
 *
 * <p>Reads and validates whatever file {@code causa.mcp.config-file} points to — the loader never
 * hardcodes which of the deployment-mode JSONs (cluster/developer/vm) that is; that choice is made
 * entirely by {@code application.yml}/{@code MCP_CONFIG_FILE}.
 *
 * <p>Uses the existing Jackson {@link ObjectMapper} and Jakarta {@link Validator} CDI beans — no
 * hand-written parsing or manual null/blank checks.
 *
 * @since 0.0.1
 */
@ApplicationScoped
public class McpSettingsLoader {

    private static final Pattern PROFILE_ID = Pattern.compile("^[a-z][a-z0-9-]{0,31}$");
    private static final Pattern REPORT_PREFIX = Pattern.compile("^[a-z][a-z0-9_]{0,14}$");

    private final ObjectMapper objectMapper;
    private final Validator validator;
    private final String configFile;

    @Inject
    public McpSettingsLoader(
            ObjectMapper objectMapper,
            Validator validator,
            @ConfigProperty(name = "causa.mcp.config-file") String configFile) {
        this.objectMapper = objectMapper;
        this.validator = validator;
        this.configFile = configFile;
    }

    /**
     * Loads and validates the MCP settings file.
     *
     * @return the parsed, validated {@link McpSettings}
     * @throws McpConfigLoadException if the file is missing/unreadable, the JSON is invalid, or
     *                                 Bean Validation fails
     */
    public McpSettings load() {
        McpSettings settings;
        try {
            settings = objectMapper.readValue(new File(configFile), McpSettings.class);
        } catch (IOException e) {
            throw new McpConfigLoadException(
                    "Failed to read MCP config file at " + configFile, "IOException", e);
        }

        if (settings == null) {
            throw new McpConfigLoadException(
                    "MCP config at " + configFile + " is empty or null", "ValidationFailed");
        }

        Set<ConstraintViolation<McpSettings>> violations = validator.validate(settings);
        if (!violations.isEmpty()) {
            String details = violations.stream()
                    .map(v -> v.getPropertyPath() + " " + v.getMessage())
                    .collect(Collectors.joining("; "));
            throw new McpConfigLoadException(
                    "MCP config at " + configFile + " failed validation: " + details, "ValidationFailed");
        }

        validateProfiles(settings);
        return settings;
    }

    /**
     * Profile rules that Bean Validation cannot express: unique ids and prefixes, known servers,
     * a non-empty resolved set, and {@code requireServers} contained in that set.
     */
    private void validateProfiles(McpSettings settings) {
        List<RcaProfile> profiles = settings.rcaProfiles();
        if (profiles.isEmpty()) {
            return;
        }

        Set<String> servers = settings.mcpServers().keySet();
        Set<String> ids = new HashSet<>();
        Set<String> prefixes = new HashSet<>();
        List<String> errors = new ArrayList<>();

        for (RcaProfile profile : profiles) {
            if (profile.id() == null || !PROFILE_ID.matcher(profile.id()).matches()) {
                errors.add("rcaProfiles id '" + profile.id() + "' must match ^[a-z][a-z0-9-]{0,31}$");
            } else if (!ids.add(profile.id())) {
                errors.add("duplicate rcaProfiles id '" + profile.id() + "'");
            }

            if (profile.reportPrefix() == null || !REPORT_PREFIX.matcher(profile.reportPrefix()).matches()) {
                errors.add("rcaProfiles reportPrefix '" + profile.reportPrefix()
                        + "' must match ^[a-z][a-z0-9_]{0,14}$");
            } else if (!prefixes.add(profile.reportPrefix())) {
                errors.add("duplicate rcaProfiles reportPrefix '" + profile.reportPrefix() + "'");
            }

            for (String name : namedServers(profile)) {
                if (!servers.contains(name)) {
                    errors.add("rcaProfiles references unknown server '" + name + "'");
                }
            }

            Set<String> resolved = profile.resolveServers(servers);
            if (resolved.isEmpty()) {
                errors.add("rcaProfiles id '" + profile.id() + "' resolves to an empty server set");
            }
            for (String required : profile.requireServers()) {
                if (!resolved.contains(required)) {
                    errors.add("rcaProfiles id '" + profile.id()
                            + "' requireServers '" + required + "' is not in the resolved server set");
                }
            }
        }

        if (!errors.isEmpty()) {
            throw new McpConfigLoadException(
                    "MCP config at " + configFile + " failed validation: " + String.join("; ", errors),
                    "ValidationFailed");
        }
    }

    private static List<String> namedServers(RcaProfile profile) {
        List<String> names = new ArrayList<>();
        addNamed(names, profile.includeServers());
        addNamed(names, profile.excludeServers());
        addNamed(names, profile.requireServers());
        return names;
    }

    private static void addNamed(List<String> names, List<String> source) {
        for (String name : source) {
            if (name != null && !"*".equals(name)) {
                names.add(name);
            }
        }
    }
}
