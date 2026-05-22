package com.chorus.engine.evals;

import org.jspecify.annotations.NonNull;

/**
 * Scorer that checks whether the expected output is contained within the actual output.
 * Case-insensitive by default. Useful for fuzzy matching where exact word order
 * or punctuation may vary.
 */
public final class ContainsScorer implements EvalScorer {

    private final boolean caseSensitive;
    private final double passThreshold;

    public ContainsScorer() {
        this(false, 0.0);
    }

    public ContainsScorer(boolean caseSensitive, double passThreshold) {
        this.caseSensitive = caseSensitive;
        if (passThreshold < 0 || passThreshold > 1) {
            throw new IllegalArgumentException("passThreshold must be in [0, 1]");
        }
        this.passThreshold = passThreshold;
    }

    @Override
    public @NonNull EvalResult score(@NonNull EvalCase testCase, @NonNull String actualOutput) {
        String expected = testCase.expectedOutput();
        if (expected == null || expected.isBlank()) {
            return new EvalResult(testCase.id(), true, 1.0, actualOutput,
                "Expected output is blank; trivial pass.");
        }

        String a = caseSensitive ? actualOutput : actualOutput.toLowerCase();
        String e = caseSensitive ? expected : expected.toLowerCase();

        boolean contained = a.contains(e);
        double score = contained ? 1.0 : 0.0;
        boolean passed = score >= passThreshold;

        String reasoning = contained
            ? "Actual output contains expected output (case-" + (caseSensitive ? "sensitive" : "insensitive") + ")."
            : "Actual output does not contain expected output.";

        return new EvalResult(testCase.id(), passed, score, actualOutput, reasoning);
    }
}
