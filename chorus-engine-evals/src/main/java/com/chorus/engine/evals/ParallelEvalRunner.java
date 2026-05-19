package com.chorus.engine.evals;

import org.jspecify.annotations.NonNull;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.StructuredTaskScope;
import java.util.function.Function;

/**
 * Concurrent evaluation runner using Java 25 StructuredTaskScope.
 * Executes all test cases in parallel with configurable concurrency.
 */
public final class ParallelEvalRunner {

    private final String runnerId;
    private final int maxConcurrency;

    public ParallelEvalRunner() {
        this("parallel-default", Runtime.getRuntime().availableProcessors());
    }

    public ParallelEvalRunner(@NonNull String runnerId, int maxConcurrency) {
        this.runnerId = runnerId;
        this.maxConcurrency = Math.max(1, maxConcurrency);
    }

    /**
     * Run the evaluation suite in parallel.
     */
    public @NonNull EvalReport run(
        @NonNull EvalDataset dataset,
        @NonNull Function<String, String> agentRunner,
        @NonNull EvalScorer scorer
    ) {
        Instant start = Instant.now();
        List<EvalCase> cases = dataset.cases();
        List<EvalResult> results = new ArrayList<>();

        // Process in batches of maxConcurrency to avoid overwhelming the agent
        for (int i = 0; i < cases.size(); i += maxConcurrency) {
            List<EvalCase> batch = cases.subList(i, Math.min(i + maxConcurrency, cases.size()));
            results.addAll(runBatch(batch, agentRunner, scorer));
        }

        int passed = (int) results.stream().filter(EvalResult::passed).count();
        double totalScore = results.stream().mapToDouble(EvalResult::score).sum();
        int totalCases = results.size();
        double passRate = totalCases > 0 ? (double) passed / totalCases : 0.0;
        double avgScore = totalCases > 0 ? totalScore / totalCases : 0.0;

        return new EvalReport(
            dataset.name(), totalCases, passed, passRate, avgScore,
            Duration.between(start, Instant.now()), results
        );
    }

    private @NonNull List<EvalResult> runBatch(
        @NonNull List<EvalCase> batch,
        @NonNull Function<String, String> agentRunner,
        @NonNull EvalScorer scorer
    ) {
        List<CompletableFuture<EvalResult>> futures = new ArrayList<>();
        for (EvalCase testCase : batch) {
            futures.add(CompletableFuture.supplyAsync(() -> {
                String actualOutput;
                try {
                    actualOutput = agentRunner.apply(testCase.input());
                } catch (Exception e) {
                    actualOutput = "ERROR: " + e.getMessage();
                }
                return scorer.score(testCase, actualOutput);
            }, java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor()));
        }

        return futures.stream()
            .map(CompletableFuture::join)
            .toList();
    }
}
