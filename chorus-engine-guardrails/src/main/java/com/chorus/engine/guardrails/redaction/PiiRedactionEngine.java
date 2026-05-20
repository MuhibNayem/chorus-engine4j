package com.chorus.engine.guardrails.redaction;

import org.jspecify.annotations.NonNull;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Hybrid PII + secret redaction engine.
 *
 * <p>Detects and redacts credit cards, SSNs, emails, phone numbers,
 * API keys, AWS keys, GitHub tokens. Thread-safe, immutable patterns.
 */
public final class PiiRedactionEngine {

    private final List<RedactionRule> rules;

    public PiiRedactionEngine() {
        this.rules = List.of(
            new RedactionRule("CREDIT_CARD",
                Pattern.compile("\\b(?:4[0-9]{12}(?:[0-9]{3})?|5[1-5][0-9]{14}|3[47][0-9]{13}|3(?:0[0-5]|[68][0-9])[0-9]{11}|6(?:011|5[0-9]{2})[0-9]{12})\\b"),
                "[CREDIT_CARD]"),
            new RedactionRule("SSN",
                Pattern.compile("\\b\\d{3}-\\d{2}-\\d{4}\\b"),
                "[SSN]"),
            new RedactionRule("EMAIL",
                Pattern.compile("[a-zA-Z0-9._%+-]+@[a-zA-Z0-9.-]+\\.[a-zA-Z]{2,}"),
                "[EMAIL]"),
            new RedactionRule("PHONE",
                Pattern.compile("\\b(?:\\+?1[-.\\s]?)?\\(?[0-9]{3}\\)?[-.\\s]?[0-9]{3}[-.\\s]?[0-9]{4}\\b"),
                "[PHONE]"),
            new RedactionRule("API_KEY",
                Pattern.compile("(?:api[_-]?key|apikey|token|password|secret|bearer)\\s*[:=]\\s*[\"']?([a-zA-Z0-9_\\-]{16,})[\"']?", Pattern.CASE_INSENSITIVE),
                "[API_KEY]"),
            new RedactionRule("AWS_KEY",
                Pattern.compile("AKIA[0-9A-Z]{16}"),
                "[AWS_KEY]"),
            new RedactionRule("GITHUB_TOKEN",
                Pattern.compile("ghp_[a-zA-Z0-9]{36}|gho_[a-zA-Z0-9]{36}|ghu_[a-zA-Z0-9]{36}|ghs_[a-zA-Z0-9]{36}|ghr_[a-zA-Z0-9]{36}"),
                "[GITHUB_TOKEN]")
        );
    }

    public @NonNull RedactionResult redact(@NonNull String input) {
        List<RedactionMatch> matches = new ArrayList<>();

        for (RedactionRule rule : rules) {
            Matcher m = rule.pattern.matcher(input);
            while (m.find()) {
                matches.add(new RedactionMatch(rule.name, m.group(), m.start(), m.end()));
            }
        }

        // Sort by start position ascending
        matches.sort((a, b) -> Integer.compare(a.start, b.start));

        // Apply replacements right-to-left so earlier positions are unaffected
        StringBuilder result = new StringBuilder(input);
        int lastStart = Integer.MAX_VALUE;
        for (int i = matches.size() - 1; i >= 0; i--) {
            RedactionMatch match = matches.get(i);
            // Skip if this match overlaps with a previously applied match to the right
            if (match.end > lastStart) continue;
            result.replace(match.start, match.end, match.replacement());
            lastStart = match.start;
        }

        return new RedactionResult(result.toString(), List.copyOf(matches));
    }

    public boolean containsPii(@NonNull String input) {
        for (RedactionRule rule : rules) {
            if (rule.pattern.matcher(input).find()) return true;
        }
        return false;
    }

    private record RedactionRule(@NonNull String name, @NonNull Pattern pattern, @NonNull String replacement) {}

    public record RedactionMatch(@NonNull String type, @NonNull String original, int start, int end) {
        public @NonNull String replacement() {
            return "[" + type + "]";
        }
    }

    public record RedactionResult(@NonNull String text, @NonNull List<RedactionMatch> matches) {
        public boolean wasRedacted() {
            return !matches.isEmpty();
        }
    }
}
