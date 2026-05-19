package com.chorus.engine.mcp.protocol;

import org.jspecify.annotations.NonNull;

import java.util.Map;
import java.util.Objects;

/**
 * Definition of an MCP tool.
 *
 * @param name        Unique identifier for the tool
 * @param description Human-readable description for LLM tool selection
 * @param inputSchema JSON Schema object describing the tool's parameters
 */
public record McpTool(
    @NonNull String name,
    @NonNull String description,
    @NonNull Map<String, Object> inputSchema
) {
    public McpTool {
        Objects.requireNonNull(name, "name cannot be null");
        Objects.requireNonNull(description, "description cannot be null");
        Objects.requireNonNull(inputSchema, "inputSchema cannot be null");
    }
}
