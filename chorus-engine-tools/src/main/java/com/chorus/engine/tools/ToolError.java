package com.chorus.engine.tools;

import org.jspecify.annotations.NonNull;

import java.time.Duration;

/**
 * Sealed error hierarchy for tool failures.
 */
public sealed interface ToolError {

    record ValidationError(@NonNull String field, @NonNull String message) implements ToolError {}

    record ExecutionError(@NonNull String command, @NonNull String stderr, int exitCode) implements ToolError {}

    record SafetyBlocked(@NonNull String reason) implements ToolError {}

    record TimeoutError(@NonNull Duration timeout) implements ToolError {}

    record NotFound(@NonNull String toolName) implements ToolError {}
}
