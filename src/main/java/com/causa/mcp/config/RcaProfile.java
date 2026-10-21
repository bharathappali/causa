package com.causa.mcp.config;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * One named RCA run. Its server set is {@code includeServers} minus {@code excludeServers}.
 * {@code "*"} in {@code includeServers} means every server in {@code mcpServers}.
 *
 * @param id                     profile id, unique, {@code ^[a-z][a-z0-9-]{0,31}$}
 * @param reportPrefix           diagnostic id prefix, unique, {@code ^[a-z][a-z0-9_]{0,14}$}
 * @param description            human-readable summary
 * @param includeServers         servers to include; {@code ["*"]} when omitted
 * @param excludeServers         servers removed from the include set; empty when omitted
 * @param requireServers         servers that must belong to the resolved set; empty when omitted
 * @param skipIfRequiredMissing  when true, skip the LLM if a required server produced no data
 */
public record RcaProfile(
        String id,
        String reportPrefix,
        String description,
        List<String> includeServers,
        List<String> excludeServers,
        List<String> requireServers,
        Boolean skipIfRequiredMissing) {

    public RcaProfile {
        includeServers = (includeServers == null || includeServers.isEmpty())
                ? List.of("*")
                : List.copyOf(includeServers);
        excludeServers = excludeServers != null ? List.copyOf(excludeServers) : List.of();
        requireServers = requireServers != null ? List.copyOf(requireServers) : List.of();
        if (skipIfRequiredMissing == null) {
            skipIfRequiredMissing = Boolean.FALSE;
        }
    }

    /**
     * Resolves this profile against the configured server names.
     *
     * @param allServers names declared in {@code mcpServers}
     * @return include set minus exclude set; {@code "*"} expands to {@code allServers}
     */
    public Set<String> resolveServers(Set<String> allServers) {
        Set<String> resolved = new LinkedHashSet<>();
        if (includeServers.contains("*")) {
            resolved.addAll(allServers);
        } else {
            resolved.addAll(includeServers);
        }
        excludeServers.forEach(resolved::remove);
        resolved.remove("*");
        return Set.copyOf(resolved);
    }
}
