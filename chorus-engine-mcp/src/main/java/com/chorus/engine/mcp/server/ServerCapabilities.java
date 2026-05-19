package com.chorus.engine.mcp.server;

import org.jspecify.annotations.NonNull;

import java.util.Map;

/**
 * Server capability declaration exchanged during initialization.
 *
 * @param tools      Server supports tools
 * @param resources  Server supports resources
 * @param prompts    Server supports prompts
 * @param logging    Server supports logging notifications
 * @param completion Server supports completion
 */
public record ServerCapabilities(boolean tools, boolean resources, boolean prompts,
                                 boolean logging, boolean completion) {

    /**
     * Default capabilities with nothing enabled.
     */
    public static @NonNull ServerCapabilities none() {
        return new ServerCapabilities(false, false, false, false, false);
    }

    /**
     * Convert to a JSON-compatible map for the initialize response.
     */
    public @NonNull Map<String, Object> toMap() {
        return Map.of(
            "tools", tools,
            "resources", resources,
            "prompts", prompts,
            "logging", logging,
            "completion", completion
        );
    }
}
