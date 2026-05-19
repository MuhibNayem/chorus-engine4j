package com.chorus.engine.eval;

/**
 * Scores 1.0 when the actual output exactly matches the expected output.
 */
public class ExactMatchScorer implements Scorer {

    private final boolean ignoreCase;

    public ExactMatchScorer() {
        this(false);
    }

    public ExactMatchScorer(boolean ignoreCase) {
        this.ignoreCase = ignoreCase;
    }

    @Override
    public double score(String expected, String actual) {
        if (expected == null || actual == null) {
            return expected == null && actual == null ? 1.0 : 0.0;
        }
        return ignoreCase ? expected.equalsIgnoreCase(actual) ? 1.0 : 0.0
                          : expected.equals(actual) ? 1.0 : 0.0;
    }
}
