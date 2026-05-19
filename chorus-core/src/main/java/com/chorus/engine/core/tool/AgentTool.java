package com.chorus.engine.core.tool;

import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * Core tool abstraction. Tools are invoked by the agent loop with named arguments.
 * Integrates with Spring AI's {@code ToolCallback} via an adapter.
 */
public interface AgentTool {

    String name();

    String description();

    /**
     * JSON Schema for tool parameters, or null for no validation.
     */
    Map<String, Object> schema();

    /**
     * Invoke the tool with parsed arguments.
     */
    CompletableFuture<String> invoke(Map<String, Object> args);
}
