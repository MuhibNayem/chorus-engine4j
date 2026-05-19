package com.chorus.engine.telemetry.redaction;

import org.jspecify.annotations.NonNull;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * PII redaction processor.
 * <p>
 * Creates redacted copies — never mutates original data.
 * Configurable regex patterns with a default set covering emails, SSNs,
 * credit cards, and phone numbers.
 */
public final class RedactionProcessor {

    private final List<Pattern> patterns;
    private final String replacement;

    /**
     * Default constructor with built-in PII patterns.
     */
    public RedactionProcessor() {
        this(List.of(
            Pattern.compile("\\b[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}\\b"),                      // email
            Pattern.compile("\\b\\d{3}-\\d{2}-\\d{4}\\b"),                                               // SSN
            Pattern.compile("\\b(?:\\d{4}[-\\s]?){3}\\d{4}\\b"),                                          // credit card
            Pattern.compile("\\b\\d{3}-\\d{3}-\\d{4}\\b"),                                               // phone dash
            Pattern.compile("\\(\\d{3}\\)\\s?\\d{3}-\\d{4}\\b")                                       // phone parens
        ), "[REDACTED]");
    }

    public RedactionProcessor(@NonNull List<Pattern> patterns, @NonNull String replacement) {
        this.patterns = List.copyOf(Objects.requireNonNull(patterns, "patterns cannot be null"));
        this.replacement = Objects.requireNonNull(replacement, "replacement cannot be null");
    }

    /**
     * Returns a redacted copy of the input string.
     */
    public @NonNull String redact(@NonNull String input) {
        Objects.requireNonNull(input, "input cannot be null");
        String result = input;
        for (Pattern pattern : patterns) {
            result = pattern.matcher(result).replaceAll(replacement);
        }
        return result;
    }

    /**
     * Returns a redacted copy of the input map.
     * Only {@link String} values are processed; other types pass through unchanged.
     */
    public @NonNull Map<String, Object> redactMap(@NonNull Map<String, Object> input) {
        Objects.requireNonNull(input, "input cannot be null");
        Map<String, Object> result = new HashMap<>();
        for (Map.Entry<String, Object> entry : input.entrySet()) {
            Object value = entry.getValue();
            if (value instanceof String s) {
                result.put(entry.getKey(), redact(s));
            } else {
                result.put(entry.getKey(), value);
            }
        }
        return Map.copyOf(result);
    }
}
