package com.chorus.engine.evals;

import org.jspecify.annotations.NonNull;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.Function;

/**
 * Runs evaluation suites: executes an agent against every case in a dataset
 * and scores the outputs.
 */
public final class EvalRunner {

    private final String runnerId;

    public EvalRunner() {
        this("default");
    }

    public EvalRunner(@NonNull String runnerId) {
        this.runnerId = Objects.requireNonNull(runnerId);
    }

    /**
     * Run the evaluation.
     *
     * @param dataset     the dataset to evaluate
     * @param agentRunner function that takes input and returns the agent's output
     * @param scorer      the scorer to use for judging correctness
     * @return the aggregated evaluation report
     */
    public @NonNull EvalReport run(
        @NonNull EvalDataset dataset,
        @NonNull Function<String, String> agentRunner,
        @NonNull EvalScorer scorer
    ) {
        Objects.requireNonNull(dataset);
        Objects.requireNonNull(agentRunner);
        Objects.requireNonNull(scorer);

        Instant start = Instant.now();
        List<EvalResult> results = new ArrayList<>();
        int passed = 0;
        double totalScore = 0.0;

        for (EvalCase testCase : dataset.cases()) {
            String actualOutput;
            try {
                actualOutput = agentRunner.apply(testCase.input());
            } catch (Exception e) {
                actualOutput = "ERROR: " + e.getMessage();
            }

            EvalResult result = scorer.score(testCase, actualOutput);
            results.add(result);

            if (result.passed()) {
                passed++;
            }
            totalScore += result.score();
        }

        Duration duration = Duration.between(start, Instant.now());
        int totalCases = results.size();
        double passRate = totalCases > 0 ? (double) passed / totalCases : 0.0;
        double avgScore = totalCases > 0 ? totalScore / totalCases : 0.0;

        return new EvalReport(
            dataset.name(),
            totalCases,
            passed,
            passRate,
            avgScore,
            duration,
            results
        );
    }
}
