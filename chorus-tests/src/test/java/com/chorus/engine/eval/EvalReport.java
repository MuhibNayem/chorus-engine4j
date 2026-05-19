package com.chorus.engine.eval;

import java.util.List;

/**
 * Aggregate report produced by an evaluation run.
 */
public record EvalReport(
    int totalCases,
    int passedCases,
    int failedCases,
    double averageScore,
    long durationMs,
    List<CaseResult> perCaseResults
) {
    /**
     * Result for a single evaluated case.
     */
    public record CaseResult(
        String caseId,
        String input,
        String expectedOutput,
        String actualOutput,
        double score,
        boolean passed,
        String message,
        long durationMs
    ) {}
}
