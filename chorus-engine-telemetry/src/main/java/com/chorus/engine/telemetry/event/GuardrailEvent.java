package com.chorus.engine.telemetry.event;

import com.chorus.engine.guardrails.GuardrailResult;
import org.jspecify.annotations.NonNull;

import java.time.Instant;
import java.util.Objects;

/**
 * Emitted when a guardrail is evaluated.
 */
public record GuardrailEvent(
    @NonNull String runId,
    @NonNull String guardrailName,
    GuardrailResult.@NonNull Action action,
    @NonNull Instant timestamp
) implements ChorusEvent {

    public GuardrailEvent {
        Objects.requireNonNull(runId, "runId cannot be null");
        Objects.requireNonNull(guardrailName, "guardrailName cannot be null");
        Objects.requireNonNull(action, "action cannot be null");
        Objects.requireNonNull(timestamp, "timestamp cannot be null");
    }

    @Override
    public @NonNull String eventType() {
        return "guardrail";
    }
}
