package com.chorus.engine.tools;

import com.chorus.engine.core.tool.AgentTool;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;

import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * Adapts a Spring AI MCP {@link ToolCallback} to Chorus {@link AgentTool}.
 * Enables MCP-discovered tools to be used within the Chorus AgentLoop.
 */
public class McpAgentTool implements AgentTool {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final ToolCallback toolCallback;
    private final Map<String, Object> schema;

    public McpAgentTool(ToolCallback toolCallback) {
        this.toolCallback = toolCallback;
        this.schema = parseSchema(toolCallback.getToolDefinition().inputSchema());
    }

    @Override
    public String name() {
        return toolCallback.getToolDefinition().name();
    }

    @Override
    public String description() {
        return toolCallback.getToolDefinition().description();
    }

    @Override
    public Map<String, Object> schema() {
        return schema;
    }

    @Override
    public CompletableFuture<String> invoke(Map<String, Object> args) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                String jsonArgs = MAPPER.writeValueAsString(args);
                return toolCallback.call(jsonArgs);
            } catch (Exception e) {
                throw new RuntimeException("MCP tool invocation failed: " + name(), e);
            }
        });
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> parseSchema(String inputSchema) {
        if (inputSchema == null || inputSchema.isBlank()) {
            return Map.of();
        }
        try {
            return MAPPER.readValue(inputSchema, Map.class);
        } catch (Exception e) {
            return Map.of();
        }
    }
}
