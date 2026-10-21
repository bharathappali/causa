package com.causa.config;

import io.smallrye.config.ConfigMapping;
import io.smallrye.config.WithDefault;
import io.smallrye.config.WithName;

/**
 * RCA Configuration
 *
 * <p>Configuration properties for Root Cause Analysis generation.
 * <p>Maps to the {@code causa.rca.*} configuration namespace.
 *
 * @since 0.0.1
 */
@ConfigMapping(prefix = "causa.rca")
public interface RcaConfig {

    /**
     * Path to the RCA prompt template YAML file.
     *
     * <p>Default: /prompts/rca-prompt-template.yml
     *
     * @return the template file path
     */
    @WithName("template-path")
    @WithDefault("/prompts/rca-prompt-template.yml")
    String templatePath();

    /**
     * How many profile RCA runs may call the LLM at once for a single alert.
     */
    @WithName("max-parallel-profiles")
    @WithDefault("2")
    int maxParallelProfiles();
}
