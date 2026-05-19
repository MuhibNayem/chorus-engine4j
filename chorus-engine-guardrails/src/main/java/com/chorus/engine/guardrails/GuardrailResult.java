package com.chorus.engine.guardrails;

import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

import java.time.Duration;
import java.util.Map;

/**
 * Immutable result of a guardrail evaluation.
 */
public record GuardrailResult(
    boolean allowed,
    @NonNull Action action,
    @Nullable String matchedContent,
    @NonNull String guardrailName,
    int tier,
    double confidence,
    @NonNull Duration latency,
    @NonNull Map<String, Object> metadata
) {
    public GuardrailResult {
        metadata = Map.copyOf(metadata);
    }

    public enum Action { ALLOW, BLOCK, WARN, REDACT, SANITIZE }

    public static @NonNull GuardrailResult allow(@NonNull String name, int tier, @NonNull Duration latency) {
        return new GuardrailResult(true, Action.ALLOW, null, name, tier, 0.0, latency, Map.of());
    }

    public static @NonNull GuardrailResult block(@NonNull String name, int tier, @NonNull String matched, double confidence, @NonNull Duration latency) {
        return new GuardrailResult(false, Action.BLOCK, matched, name, tier, confidence, latency, Map.of());
    }

    public static @NonNull GuardrailResult redact(@NonNull String name, int tier, @NonNull String matched, double confidence, @NonNull Duration latency) {
        return new GuardrailResult(true, Action.REDACT, matched, name, tier, confidence, latency, Map.of("redacted", true));
    }
}
