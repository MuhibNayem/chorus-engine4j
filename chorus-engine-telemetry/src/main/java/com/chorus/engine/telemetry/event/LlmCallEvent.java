package com.chorus.engine.telemetry.event;

import org.jspecify.annotations.NonNull;

import java.time.Duration;
import java.time.Instant;
import java.util.Objects;

/**
 * Emitted for every LLM API call.
 */
public record LlmCallEvent(
    @NonNull String runId,
    @NonNull String provider,
    @NonNull String model,
    int inputTokens,
    int outputTokens,
    @NonNull Duration latency,
    @NonNull Instant timestamp
) implements ChorusEvent {

    public LlmCallEvent {
        Objects.requireNonNull(runId, "runId cannot be null");
        Objects.requireNonNull(provider, "provider cannot be null");
        Objects.requireNonNull(model, "model cannot be null");
        Objects.requireNonNull(latency, "latency cannot be null");
        Objects.requireNonNull(timestamp, "timestamp cannot be null");
        if (inputTokens < 0) throw new IllegalArgumentException("inputTokens must be >= 0");
        if (outputTokens < 0) throw new IllegalArgumentException("outputTokens must be >= 0");
    }

    @Override
    public @NonNull String eventType() {
        return "llm.call";
    }
}
