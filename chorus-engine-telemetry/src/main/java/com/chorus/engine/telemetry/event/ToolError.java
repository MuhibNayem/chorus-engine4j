package com.chorus.engine.telemetry.event;

import org.jspecify.annotations.NonNull;

import java.util.Objects;

/**
 * Immutable description of a tool execution failure.
 */
public record ToolError(
    @NonNull String errorType,
    @NonNull String errorMessage,
    boolean retryable
) {

    public ToolError {
        Objects.requireNonNull(errorType, "errorType cannot be null");
        Objects.requireNonNull(errorMessage, "errorMessage cannot be null");
    }
}
