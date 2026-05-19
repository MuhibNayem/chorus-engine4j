package com.chorus.engine.evals;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class EvalRunnerTest {

    @Test
    void runEvaluationAllPass() {
        EvalDataset dataset = EvalDataset.of("test", List.of(
            new EvalCase("1", "hello", "world", Map.of()),
            new EvalCase("2", "foo", "bar", Map.of())
        ));

        EvalRunner runner = new EvalRunner();
        EvalReport report = runner.run(dataset, input -> input.equals("hello") ? "world" : "bar", new ExactMatchScorer());

        assertEquals("test", report.datasetName());
        assertEquals(2, report.totalCases());
        assertEquals(2, report.passed());
        assertEquals(1.0, report.passRate(), 0.001);
        assertEquals(1.0, report.avgScore(), 0.001);
        assertNotNull(report.duration());
        assertEquals(2, report.results().size());
    }

    @Test
    void runEvaluationPartialPass() {
        EvalDataset dataset = EvalDataset.of("test", List.of(
            new EvalCase("1", "hello", "world", Map.of()),
            new EvalCase("2", "foo", "bar", Map.of())
        ));

        EvalRunner runner = new EvalRunner();
        EvalReport report = runner.run(dataset, input -> "wrong", new ExactMatchScorer());

        assertEquals(0, report.passed());
        assertEquals(0.0, report.passRate(), 0.001);
        assertEquals(0.0, report.avgScore(), 0.001);
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

        assertEquals(1, report.totalCases());
        assertEquals(0, report.passed());
        assertTrue(report.results().get(0).actualOutput().contains("ERROR"));
    }

    @Test
    void emptyDataset() {
        EvalDataset dataset = EvalDataset.of("empty", List.of());

        EvalRunner runner = new EvalRunner();
        EvalReport report = runner.run(dataset, input -> input, new ExactMatchScorer());

        assertEquals(0, report.totalCases());
        assertEquals(0.0, report.passRate(), 0.001);
        assertEquals(0.0, report.avgScore(), 0.001);
    }
}
