package com.chorus.engine.evals;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class EvalRunnerTest {

    @Test
    void runEvaluationAllPass() {
        EvalDataset dataset = EvalDataset.of("test", List.of(
            new EvalCase("1", "hello", "world", Map.of()),
            new EvalCase("2", "foo", "bar", Map.of())
        ));

        EvalRunner runner = new EvalRunner();
        EvalReport report = runner.run(dataset, input -> input.equals("hello") ? "world" : "bar", new ExactMatchScorer());

        assertThat(report.datasetName()).isEqualTo("test");
        assertThat(report.totalCases()).isEqualTo(2);
        assertThat(report.passed()).isEqualTo(2);
        assertThat(report.passRate()).isCloseTo(1.0, org.assertj.core.data.Offset.offset(0.001));
        assertThat(report.avgScore()).isCloseTo(1.0, org.assertj.core.data.Offset.offset(0.001));
        assertThat(report.duration()).isNotNull();
        assertThat(report.results()).hasSize(2);
    }

    @Test
    void runEvaluationPartialPass() {
        EvalDataset dataset = EvalDataset.of("test", List.of(
            new EvalCase("1", "hello", "world", Map.of()),
            new EvalCase("2", "foo", "bar", Map.of())
        ));

        EvalRunner runner = new EvalRunner();
        EvalReport report = runner.run(dataset, input -> "wrong", new ExactMatchScorer());

        assertThat(report.passed()).isEqualTo(0);
        assertThat(report.passRate()).isCloseTo(0.0, org.assertj.core.data.Offset.offset(0.001));
        assertThat(report.avgScore()).isCloseTo(0.0, org.assertj.core.data.Offset.offset(0.001));
    }

    @Test
    void runEvaluationHandlesException() {
        EvalDataset dataset = EvalDataset.of("test", List.of(
            new EvalCase("1", "hello", "world", Map.of())
        ));

        EvalRunner runner = new EvalRunner();
        EvalReport report = runner.run(dataset, input -> {
            throw new RuntimeException("boom");
        }, new ExactMatchScorer());

        assertThat(report.totalCases()).isEqualTo(1);
        assertThat(report.passed()).isEqualTo(0);
        assertThat(report.results().get(0).actualOutput()).contains("ERROR");
    }

    @Test
    void emptyDataset() {
        EvalDataset dataset = EvalDataset.of("empty", List.of());

        EvalRunner runner = new EvalRunner();
        EvalReport report = runner.run(dataset, input -> input, new ExactMatchScorer());

        assertThat(report.totalCases()).isEqualTo(0);
        assertThat(report.passRate()).isCloseTo(0.0, org.assertj.core.data.Offset.offset(0.001));
        assertThat(report.avgScore()).isCloseTo(0.0, org.assertj.core.data.Offset.offset(0.001));
    }

    // --- Expanded tests ---

    @Test
    void nullDatasetRejection() {
        EvalRunner runner = new EvalRunner();
        assertThatThrownBy(() -> runner.run(null, input -> input, new ExactMatchScorer()))
            .isInstanceOf(NullPointerException.class);
    }

    @Test
    void nullAgentRunnerRejection() {
        EvalDataset dataset = EvalDataset.of("test", List.of(new EvalCase("1", "a", "b", Map.of())));
        EvalRunner runner = new EvalRunner();
        assertThatThrownBy(() -> runner.run(dataset, null, new ExactMatchScorer()))
            .isInstanceOf(NullPointerException.class);
    }

    @Test
    void nullScorerRejection() {
        EvalDataset dataset = EvalDataset.of("test", List.of(new EvalCase("1", "a", "b", Map.of())));
        EvalRunner runner = new EvalRunner();
        assertThatThrownBy(() -> runner.run(dataset, input -> input, null))
            .isInstanceOf(NullPointerException.class);
    }

    @Test
    void scorerReturningNullResult() {
        EvalDataset dataset = EvalDataset.of("test", List.of(
            new EvalCase("1", "hello", "world", Map.of())
        ));

        EvalScorer nullScorer = (testCase, actualOutput) -> null;
        EvalRunner runner = new EvalRunner();

        assertThatThrownBy(() -> runner.run(dataset, input -> "world", nullScorer))
            .isInstanceOf(NullPointerException.class);
    }

    @Test
    void agentRunnerReturningNull() {
        EvalDataset dataset = EvalDataset.of("test", List.of(
            new EvalCase("1", "hello", "world", Map.of())
        ));

        EvalRunner runner = new EvalRunner();
        EvalReport report = runner.run(dataset, input -> null, new ExactMatchScorer());

        assertThat(report.totalCases()).isEqualTo(1);
        assertThat(report.passed()).isEqualTo(0);
    }

    @Test
    void singleCaseDataset() {
        EvalDataset dataset = EvalDataset.of("single", List.of(
            new EvalCase("1", "hello", "world", Map.of())
        ));

        EvalRunner runner = new EvalRunner();
        EvalReport report = runner.run(dataset, input -> "world", new ExactMatchScorer());

        assertThat(report.totalCases()).isEqualTo(1);
        assertThat(report.passed()).isEqualTo(1);
        assertThat(report.passRate()).isCloseTo(1.0, org.assertj.core.data.Offset.offset(0.001));
    }
}
