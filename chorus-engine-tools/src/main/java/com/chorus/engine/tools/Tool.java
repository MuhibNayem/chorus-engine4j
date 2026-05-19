package com.chorus.engine.tools;

import com.chorus.engine.core.reactive.CancellationToken;
import com.chorus.engine.core.result.Result;
import org.jspecify.annotations.NonNull;

import java.util.Map;

/**
 * Pluggable tool interface. Every tool has:
 * <ul>
 *   <li>name: snake_case identifier</li>
 *   <li>description: natural language description for LLM tool selection</li>
 *   <li>parametersSchema: JSON Schema object ({@code Map<String,Object>}) describing args</li>
 *   <li>execute: runs the tool with args and a cancellation token</li>
 * </ul>
 */
public interface Tool {

    @NonNull String name();

    @NonNull String description();

    @NonNull Map<String, Object> parametersSchema();

    @NonNull Result<ToolOutput, ToolError> execute(@NonNull Map<String, Object> args, @NonNull CancellationToken token);
}
