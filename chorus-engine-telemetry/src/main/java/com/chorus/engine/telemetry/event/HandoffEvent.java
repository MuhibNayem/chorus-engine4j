package com.chorus.engine.telemetry.event;

import org.jspecify.annotations.NonNull;

import java.time.Instant;
import java.util.Objects;

/**
 * Emitted when control is handed off from one agent to another.
 */
public record HandoffEvent(
    @NonNull String runId,
    @NonNull String fromAgent,
    @NonNull String toAgent,
    @NonNull String reason,
    @NonNull Instant timestamp
) implements ChorusEvent {

    public HandoffEvent {
        Objects.requireNonNull(runId, "runId cannot be null");
        Objects.requireNonNull(fromAgent, "fromAgent cannot be null");
        Objects.requireNonNull(toAgent, "toAgent cannot be null");
        Objects.requireNonNull(reason, "reason cannot be null");
        Objects.requireNonNull(timestamp, "timestamp cannot be null");
    }

    @Override
    public @NonNull String eventType() {
        return "handoff";
    }
}
