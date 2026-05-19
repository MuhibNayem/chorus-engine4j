package com.chorus.engine.guardrails.tier;

import com.chorus.engine.guardrails.Guardrail;
import com.chorus.engine.guardrails.GuardrailResult;
import org.jspecify.annotations.NonNull;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Tier 1 guardrail using compiled regex patterns.
 * Thread-safe: patterns are immutable after construction.
 */
public final class RegexGuardrail implements Guardrail {

    private final String name;
    private final Pattern pattern;
    private final GuardrailResult.Action action;
    private final double confidence;

    public RegexGuardrail(@NonNull String name, @NonNull Pattern pattern, GuardrailResult.Action action, double confidence) {
        this.name = name;
        this.pattern = pattern;
        this.action = action;
        this.confidence = confidence;
    }

    public static @NonNull RegexGuardrail block(@NonNull String name, @NonNull String regex) {
        return new RegexGuardrail(name, Pattern.compile(regex, Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CHARACTER_CLASS), GuardrailResult.Action.BLOCK, 1.0);
    }

    public static @NonNull RegexGuardrail redact(@NonNull String name, @NonNull String regex) {
        return new RegexGuardrail(name, Pattern.compile(regex, Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CHARACTER_CLASS), GuardrailResult.Action.REDACT, 0.95);
    }

    @Override
    public @NonNull String name() { return name; }

    @Override
    public int tier() { return 1; }

    @Override
    public @NonNull GuardrailResult evaluate(@NonNull String input, @NonNull GuardrailContext context) {
        Instant start = Instant.now();
        Matcher m = pattern.matcher(input);
        if (m.find()) {
            String matched = m.group();
            Duration latency = Duration.between(start, Instant.now());
            return switch (action) {
                case BLOCK -> GuardrailResult.block(name, 1, matched, confidence, latency);
                case REDACT -> GuardrailResult.redact(name, 1, matched, confidence, latency);
                default -> GuardrailResult.allow(name, 1, latency);
            };
        }
        return GuardrailResult.allow(name, 1, Duration.between(start, Instant.now()));
    }
}
