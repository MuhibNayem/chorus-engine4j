package com.chorus.engine.telemetry.event;

import com.chorus.engine.llm.retry.CircuitBreaker;
import org.jspecify.annotations.NonNull;

import java.time.Instant;
import java.util.Objects;

/**
 * Emitted when a circuit breaker changes state.
 */
public record CircuitBreakerEvent(
    @NonNull String agentId,
    CircuitBreaker.@NonNull State state,
    int failureCount,
    @NonNull Instant timestamp
) implements ChorusEvent {

    public CircuitBreakerEvent {
        Objects.requireNonNull(agentId, "agentId cannot be null");
        Objects.requireNonNull(state, "state cannot be null");
        Objects.requireNonNull(timestamp, "timestamp cannot be null");
        if (failureCount < 0) throw new IllegalArgumentException("failureCount must be >= 0");
    }

    @Override
    public @NonNull String runId() {
        return agentId;
    }

    @Override
    public @NonNull String eventType() {
        return "circuit.breaker";
    }
}
