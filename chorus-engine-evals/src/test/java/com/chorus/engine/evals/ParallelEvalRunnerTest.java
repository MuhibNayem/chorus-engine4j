package com.chorus.engine.evals;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ParallelEvalRunnerTest {

    @Test
    void runParallelEvaluations() {
        ParallelEvalRunner runner = new ParallelEvalRunner("test", 4);

        EvalDataset dataset = EvalDataset.of("test-dataset", List.of(
            new EvalCase("c1", "What is 2+2?", "4", java.util.Map.of()),
            new EvalCase("c2", "What is 3+3?", "6", java.util.Map.of()),
            new EvalCase("c3", "What is 4+4?", "8", java.util.Map.of())
        ));

        EvalReport report = runner.run(dataset, input -> {
            return switch (input) {
                case "What is 2+2?" -> "4";
                case "What is 3+3?" -> "6";
                case "What is 4+4?" -> "8";
                default -> "unknown";
            };
        }, new ExactMatchScorer());

        assertThat(report.totalCases()).isEqualTo(3);
        assertThat(report.passed()).isEqualTo(3);
        assertThat(report.passRate()).isEqualTo(1.0);
    }

    @Test
    void handlesAgentErrors() {
        ParallelEvalRunner runner = new ParallelEvalRunner("test", 2);

        EvalDataset dataset = EvalDataset.of("test-dataset", List.of(
            new EvalCase("c1", "input1", "expected", java.util.Map.of())
        ));

        EvalReport report = runner.run(dataset, input -> {
            throw new RuntimeException("Agent failure");
        }, new ExactMatchScorer());

        assertThat(report.passed()).isEqualTo(0);
        assertThat(report.results().get(0).actualOutput()).contains("ERROR");
    }

    // --- Expanded tests ---

    @Test
    void emptyDataset() {
        ParallelEvalRunner runner = new ParallelEvalRunner("test", 4);
        EvalDataset dataset = EvalDataset.of("empty", List.of());

        EvalReport report = runner.run(dataset, input -> input, new ExactMatchScorer());

        assertThat(report.totalCases()).isEqualTo(0);
        assertThat(report.passed()).isEqualTo(0);
        assertThat(report.passRate()).isEqualTo(0.0);
        assertThat(report.avgScore()).isEqualTo(0.0);
    }

    @Test
    void singleCase() {
        ParallelEvalRunner runner = new ParallelEvalRunner("test", 4);
        EvalDataset dataset = EvalDataset.of("single", List.of(
            new EvalCase("c1", "hello", "world", java.util.Map.of())
        ));

        EvalReport report = runner.run(dataset, input -> "world", new ExactMatchScorer());

        assertThat(report.totalCases()).isEqualTo(1);
        assertThat(report.passed()).isEqualTo(1);
        assertThat(report.passRate()).isEqualTo(1.0);
    }

    @Test
    void maxConcurrencyOneRunsSequentially() {
        ParallelEvalRunner runner = new ParallelEvalRunner("test", 1);
        EvalDataset dataset = EvalDataset.of("seq", List.of(
            new EvalCase("c1", "a", "a", java.util.Map.of()),
            new EvalCase("c2", "b", "b", java.util.Map.of()),
            new EvalCase("c3", "c", "c", java.util.Map.of())
        ));

        EvalReport report = runner.run(dataset, input -> input, new ExactMatchScorer());

        assertThat(report.totalCases()).isEqualTo(3);
        assertThat(report.passed()).isEqualTo(3);
    }

    @Test
    void scorerThrowingExceptionPropagates() {
        ParallelEvalRunner runner = new ParallelEvalRunner("test", 2);
        EvalDataset dataset = EvalDataset.of("fail", List.of(
            new EvalCase("c1", "a", "a", java.util.Map.of())
        ));

        EvalScorer throwingScorer = (testCase, actualOutput) -> {
            throw new RuntimeException("Scorer explosion");
        };

        assertThatThrownBy(() -> runner.run(dataset, input -> input, throwingScorer))
            .isInstanceOf(java.util.concurrent.CompletionException.class)
            .hasCauseInstanceOf(RuntimeException.class)
            .hasRootCauseMessage("Scorer explosion");
    }

    @Test
    void allFailuresBatch() {
        ParallelEvalRunner runner = new ParallelEvalRunner("test", 2);
        EvalDataset dataset = EvalDataset.of("all-fail", List.of(
            new EvalCase("c1", "a", "expected-a", java.util.Map.of()),
            new EvalCase("c2", "b", "expected-b", java.util.Map.of()),
            new EvalCase("c3", "c", "expected-c", java.util.Map.of())
        ));

        EvalReport report = runner.run(dataset, input -> "wrong-every-time", new ExactMatchScorer());

        assertThat(report.totalCases()).isEqualTo(3);
        assertThat(report.passed()).isEqualTo(0);
        assertThat(report.passRate()).isEqualTo(0.0);
        assertThat(report.avgScore()).isEqualTo(0.0);
        assertThat(report.results()).allMatch(r -> !r.passed());
    }
}
