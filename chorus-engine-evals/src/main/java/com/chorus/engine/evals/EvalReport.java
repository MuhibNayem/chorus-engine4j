package com.chorus.engine.evals;

import org.jspecify.annotations.NonNull;

import java.time.Duration;
import java.util.List;
import java.util.Objects;

/**
 * Aggregated evaluation report for a dataset.
 *
 * @param datasetName name of the evaluated dataset
 * @param totalCases  total number of cases evaluated
 * @param passed      number of cases that passed
 * @param passRate    fraction of cases that passed (0.0 - 1.0)
 * @param avgScore    average score across all cases
 * @param duration    total evaluation duration
 * @param results     individual case results
 */
public record EvalReport(
    @NonNull String datasetName,
    int totalCases,
    int passed,
    double passRate,
    double avgScore,
    @NonNull Duration duration,
    @NonNull List<EvalResult> results
) {
    public EvalReport {
        Objects.requireNonNull(datasetName, "datasetName cannot be null");
        Objects.requireNonNull(duration, "duration cannot be null");
        Objects.requireNonNull(results, "results cannot be null");
    }
}
