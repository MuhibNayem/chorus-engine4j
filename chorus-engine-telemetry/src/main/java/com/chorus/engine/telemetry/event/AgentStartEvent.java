package com.chorus.engine.telemetry.event;

import org.jspecify.annotations.NonNull;

import java.time.Instant;
import java.util.Objects;

/**
 * Emitted when an agent run begins.
 */
public record AgentStartEvent(
    @NonNull String runId,
    @NonNull String agentId,
    @NonNull String model,
    @NonNull Instant timestamp
) implements ChorusEvent {

    public AgentStartEvent {
        Objects.requireNonNull(runId, "runId cannot be null");
        Objects.requireNonNull(agentId, "agentId cannot be null");
        Objects.requireNonNull(model, "model cannot be null");
        Objects.requireNonNull(timestamp, "timestamp cannot be null");
    }

    @Override
    public @NonNull String eventType() {
        return "agent.start";
    }
}
