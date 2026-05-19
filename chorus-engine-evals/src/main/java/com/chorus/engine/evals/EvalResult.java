package com.chorus.engine.evals;

import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

import java.util.Objects;

/**
 * Result of evaluating a single test case.
 *
 * @param caseId       Reference to the EvalCase id
 * @param passed       Whether the test passed according to the scorer
 * @param score        Numeric score (0.0 - 1.0 typical, but scorer-defined)
 * @param actualOutput The output produced by the agent
 * @param reasoning    Optional explanation from the scorer
 */
public record EvalResult(
    @NonNull String caseId,
    boolean passed,
    double score,
    @NonNull String actualOutput,
    @Nullable String reasoning
) {
    public EvalResult {
        Objects.requireNonNull(caseId, "caseId cannot be null");
        Objects.requireNonNull(actualOutput, "actualOutput cannot be null");
    }
}
