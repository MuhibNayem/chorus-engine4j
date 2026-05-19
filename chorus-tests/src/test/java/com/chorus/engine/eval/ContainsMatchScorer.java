package com.chorus.engine.eval;

/**
 * Scores 1.0 when the actual output contains the expected output.
 */
public class ContainsMatchScorer implements Scorer {

    private final boolean ignoreCase;

    public ContainsMatchScorer() {
        this(false);
    }

    public ContainsMatchScorer(boolean ignoreCase) {
        this.ignoreCase = ignoreCase;
    }

    @Override
    public double score(String expected, String actual) {
        if (expected == null || actual == null) {
            return 0.0;
        }
        if (ignoreCase) {
            return actual.toLowerCase().contains(expected.toLowerCase()) ? 1.0 : 0.0;
        }
        return actual.contains(expected) ? 1.0 : 0.0;
    }
}
