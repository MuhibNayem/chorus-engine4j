package com.chorus.engine.guardrails.tier;

import com.chorus.engine.guardrails.Guardrail;
import com.chorus.engine.guardrails.GuardrailResult;
import org.jspecify.annotations.NonNull;

import java.time.Duration;
import java.time.Instant;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Tier 1 guardrail using exact keyword matching with word boundaries.
 * Uses compiled case-insensitive matching for efficiency.
 */
public final class KeywordGuardrail implements Guardrail {

    private final String name;
    private final Set<String> keywords;
    private final GuardrailResult.Action action;
    private final Pattern combinedPattern;

    public KeywordGuardrail(@NonNull String name, @NonNull Set<String> keywords, GuardrailResult.@NonNull Action action) {
        this.name = name;
        this.keywords = Set.copyOf(keywords);
        this.action = action;
        // Build single regex with alternation for efficiency
        String alternation = keywords.stream()
            .map(Pattern::quote)
            .reduce((a, b) -> a + "|" + b)
            .orElse("(?!))"); // never-match if empty
        this.combinedPattern = Pattern.compile("\\b(?:" + alternation + ")\\b",
            Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CHARACTER_CLASS);
    }

    @Override
    public @NonNull String name() { return name; }

    @Override
    public int tier() { return 1; }

    @Override
    public @NonNull GuardrailResult evaluate(@NonNull String input, @NonNull GuardrailContext context) {
        Instant start = Instant.now();
        var matcher = combinedPattern.matcher(input);
        if (matcher.find()) {
            String matched = matcher.group();
            Duration latency = Duration.between(start, Instant.now());
            return switch (action) {
                case BLOCK -> GuardrailResult.block(name, 1, matched, 1.0, latency);
                case REDACT -> GuardrailResult.redact(name, 1, matched, 0.95, latency);
                default -> GuardrailResult.allow(name, 1, latency);
            };
        }
        return GuardrailResult.allow(name, 1, Duration.between(start, Instant.now()));
    }
}
