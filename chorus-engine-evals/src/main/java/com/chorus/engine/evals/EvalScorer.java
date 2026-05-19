package com.chorus.engine.evals;

import org.jspecify.annotations.NonNull;

/**
 * Interface for scoring the output of an evaluation case.
 */
public interface EvalScorer {

    /**
     * Score the actual output against the expected output for a given test case.
     *
     * @param testCase     the evaluation case
     * @param actualOutput the output produced by the system under test
     * @return the evaluation result
     */
    @NonNull EvalResult score(@NonNull EvalCase testCase, @NonNull String actualOutput);
}
