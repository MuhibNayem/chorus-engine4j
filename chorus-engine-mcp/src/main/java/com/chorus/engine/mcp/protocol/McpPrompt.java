package com.chorus.engine.mcp.protocol;

import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

import java.util.List;
import java.util.Objects;

/**
 * Definition of an MCP prompt template.
 *
 * @param name        Unique identifier for the prompt
 * @param description Optional description
 * @param arguments   List of arguments the prompt accepts
 */
public record McpPrompt(
    @NonNull String name,
    @Nullable String description,
    @NonNull List<McpPromptArgument> arguments
) {
    public McpPrompt {
        Objects.requireNonNull(name, "name cannot be null");
        Objects.requireNonNull(arguments, "arguments cannot be null");
    }

    /**
     * A single argument definition for a prompt.
     *
     * @param name        Argument name
     * @param description Optional description
     * @param required    Whether the argument is required
     */
    public record McpPromptArgument(
        @NonNull String name,
        @Nullable String description,
        boolean required
    ) {
        public McpPromptArgument {
            Objects.requireNonNull(name, "name cannot be null");
        }
    }
}
