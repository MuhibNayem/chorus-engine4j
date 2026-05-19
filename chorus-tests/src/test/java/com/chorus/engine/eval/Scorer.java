package com.chorus.engine.eval;

/**
 * Strategy for scoring an actual output against an expected reference.
 * Returns a value in the range [0.0, 1.0].
 */
@FunctionalInterface
public interface Scorer {
    double score(String expected, String actual);
}
