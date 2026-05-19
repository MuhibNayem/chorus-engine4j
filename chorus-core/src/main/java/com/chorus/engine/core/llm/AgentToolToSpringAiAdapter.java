package com.chorus.engine.core.llm;

import com.chorus.engine.core.tool.AgentTool;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.ai.tool.metadata.ToolMetadata;

import java.util.Map;

/**
 * Adapts a Chorus {@link AgentTool} to Spring AI's {@link ToolCallback}.
 */
public class AgentToolToSpringAiAdapter implements ToolCallback {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final AgentTool agentTool;
    private final ToolDefinition toolDefinition;

    public AgentToolToSpringAiAdapter(AgentTool agentTool) {
        this.agentTool = agentTool;
        this.toolDefinition = ToolDefinition.builder()
            .name(agentTool.name())
            .description(agentTool.description())
            .inputSchema(schemaToString(agentTool.schema()))
            .build();
    }

    @Override
    public ToolDefinition getToolDefinition() {
        return toolDefinition;
    }

    @Override
    public ToolMetadata getToolMetadata() {
        return ToolMetadata.builder().build();
    }

    @Override
    public String call(String toolInput) {
        try {
            Map<String, Object> args;
            if (toolInput == null || toolInput.isBlank()) {
                args = Map.of();
            } else {
                args = MAPPER.readValue(toolInput, Map.class);
            }
            return agentTool.invoke(args).join();
        } catch (Exception e) {
            throw new RuntimeException("Tool execution failed: " + agentTool.name(), e);
        }
    }

    private static String schemaToString(Map<String, Object> schema) {
        if (schema == null || schema.isEmpty()) {
            return "{}";
        }
        try {
            return MAPPER.writeValueAsString(schema);
        } catch (Exception e) {
            return "{}";
        }
    }
}
