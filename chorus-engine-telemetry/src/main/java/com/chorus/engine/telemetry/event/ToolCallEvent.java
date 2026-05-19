package com.chorus.engine.telemetry.event;

import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

import java.time.Duration;
import java.time.Instant;
import java.util.Objects;

/**
 * Emitted when a tool is invoked by an agent.
 */
public record ToolCallEvent(
    @NonNull String runId,
    @NonNull String agentId,
    @NonNull String toolName,
    @NonNull Duration latency,
    @Nullable ToolError error,
    @NonNull Instant timestamp
) implements ChorusEvent {

    public ToolCallEvent {
        Objects.requireNonNull(runId, "runId cannot be null");
        Objects.requireNonNull(agentId, "agentId cannot be null");
        Objects.requireNonNull(toolName, "toolName cannot be null");
        Objects.requireNonNull(latency, "latency cannot be null");
        Objects.requireNonNull(timestamp, "timestamp cannot be null");
    }

    @Override
    public @NonNull String eventType() {
        return "tool.call";
    }
}
