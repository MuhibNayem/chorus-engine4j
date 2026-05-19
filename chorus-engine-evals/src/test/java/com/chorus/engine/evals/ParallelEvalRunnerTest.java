package com.chorus.engine.evals;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

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
}
