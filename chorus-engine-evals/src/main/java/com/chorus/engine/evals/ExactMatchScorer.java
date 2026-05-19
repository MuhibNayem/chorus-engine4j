package com.chorus.engine.evals;

import org.jspecify.annotations.NonNull;

import java.util.Objects;

/**
 * Scorer that performs exact string matching (case-sensitive by default).
 */
public final class ExactMatchScorer implements EvalScorer {

    private final boolean ignoreCase;
    private final boolean trimWhitespace;

    public ExactMatchScorer() {
        this(false, true);
    }

    public ExactMatchScorer(boolean ignoreCase, boolean trimWhitespace) {
        this.ignoreCase = ignoreCase;
        this.trimWhitespace = trimWhitespace;
    }

    @Override
    public @NonNull EvalResult score(@NonNull EvalCase testCase, @NonNull String actualOutput) {
        String expected = testCase.expectedOutput();
        String actual = actualOutput;

        if (trimWhitespace) {
            expected = expected.trim();
            actual = actual.trim();
        }

        boolean passed = ignoreCase
            ? expected.equalsIgnoreCase(actual)
            : expected.equals(actual);

        double score = passed ? 1.0 : 0.0;

        String reasoning = passed
            ? "Exact match"
            : "Expected: '" + expected + "' but got: '" + actual + "'";

        return new EvalResult(testCase.id(), passed, score, actualOutput, reasoning);
    }
}
