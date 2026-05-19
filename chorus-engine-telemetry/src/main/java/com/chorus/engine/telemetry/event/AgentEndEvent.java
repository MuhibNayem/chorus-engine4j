package com.chorus.engine.telemetry.event;

import com.chorus.engine.core.context.TokenCount;
import org.jspecify.annotations.NonNull;

import java.time.Duration;
import java.time.Instant;
import java.util.Objects;

/**
 * Emitted when an agent run completes.
 */
public record AgentEndEvent(
    @NonNull String runId,
    @NonNull String agentId,
    @NonNull TokenCount tokens,
    @NonNull Duration latency,
    @NonNull Instant timestamp
) implements ChorusEvent {

    public AgentEndEvent {
        Objects.requireNonNull(runId, "runId cannot be null");
        Objects.requireNonNull(agentId, "agentId cannot be null");
        Objects.requireNonNull(tokens, "tokens cannot be null");
        Objects.requireNonNull(latency, "latency cannot be null");
        Objects.requireNonNull(timestamp, "timestamp cannot be null");
    }

    @Override
    public @NonNull String eventType() {
        return "agent.end";
    }
}
