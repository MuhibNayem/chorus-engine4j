package com.chorus.engine.eval;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.*;

class EvalHarnessTest {

    @Test
    void testLoadDataset() {
        List<EvalCase> cases = EvalHarness.loadDataset("evals/sample-dataset.json");
        assertThat(cases).hasSize(5);

        EvalCase first = cases.get(0);
        assertThat(first.id()).isEqualTo("calc-001");
        assertThat(first.input()).isEqualTo("What is 2 + 2?");
        assertThat(first.expectedOutput()).isEqualTo("4");
        assertThat(first.rubric()).isEqualTo("The answer must be exactly 4.");
        assertThat(first.tags()).containsExactly("math", "addition");
    }

    @Test
    void testExactMatchScoring() {
        List<Scorer> scorers = List.of(new ExactMatchScorer());
        EvalHarness harness = new EvalHarness(scorers, 1.0);

        List<EvalCase> cases = List.of(
            new EvalCase("e1", "input", "hello", "rubric", List.of()),
            new EvalCase("e2", "input", "hello", "rubric", List.of())
        );

        Function<EvalCase, String> runner = c -> c.id().equals("e1") ? "hello" : "world";
        EvalReport report = harness.run(cases, runner);

        assertThat(report.totalCases()).isEqualTo(2);
        assertThat(report.passedCases()).isEqualTo(1);
        assertThat(report.failedCases()).isEqualTo(1);
        assertThat(report.averageScore()).isEqualTo(0.5);
        assertThat(report.perCaseResults()).hasSize(2);

        EvalReport.CaseResult pass = report.perCaseResults().get(0);
        assertThat(pass.caseId()).isEqualTo("e1");
        assertThat(pass.passed()).isTrue();
        assertThat(pass.score()).isEqualTo(1.0);

        EvalReport.CaseResult fail = report.perCaseResults().get(1);
        assertThat(fail.caseId()).isEqualTo("e2");
        assertThat(fail.passed()).isFalse();
        assertThat(fail.score()).isEqualTo(0.0);
    }

    @Test
    void testContainsMatchScoring() {
        List<Scorer> scorers = List.of(new ContainsMatchScorer());
        EvalHarness harness = new EvalHarness(scorers, 1.0);

        List<EvalCase> cases = List.of(
            new EvalCase("c1", "input", "42", "rubric", List.of())
        );

        EvalReport report = harness.run(cases, c -> "The answer is 42.");
        assertThat(report.passedCases()).isEqualTo(1);
        assertThat(report.perCaseResults().get(0).score()).isEqualTo(1.0);
    }

    @Test
    void testMultipleScorersAverage() {
        List<Scorer> scorers = List.of(
            new ExactMatchScorer(),
            new ContainsMatchScorer()
        );
        EvalHarness harness = new EvalHarness(scorers, 0.5);

        List<EvalCase> cases = List.of(
            new EvalCase("m1", "input", "42", "rubric", List.of())
        );

        // Exact match fails (0.0), contains match passes (1.0) -> avg 0.5
        EvalReport report = harness.run(cases, c -> "The answer is 42.");
        assertThat(report.averageScore()).isEqualTo(0.5);
        assertThat(report.passedCases()).isEqualTo(1);
    }

    @Test
    void testAssertThatMatchesRubricExact() {
        EvalHarness.assertThat("hello").matchesRubric("hello");
    }

    @Test
    void testAssertThatMatchesRubricWithScorer() {
        EvalHarness.assertThat("The answer is 42.")
            .matchesRubric("42", "contains 42", new ContainsMatchScorer(), 1.0);
    }

    @Test
    void testAssertThatContainsExpected() {
        EvalHarness.assertThat("The answer is 42.").containsExpected("42");
    }

    @Test
    void testRegressionDetector() {
        List<EvalReport.CaseResult> baselineResults = List.of(
            new EvalReport.CaseResult("r1", "in", "exp", "act", 1.0, true, "", 0L),
            new EvalReport.CaseResult("r2", "in", "exp", "act", 0.9, true, "", 0L)
        );
        EvalReport baseline = new EvalReport(2, 2, 0, 0.95, 100L, baselineResults);

        List<EvalReport.CaseResult> currentResults = List.of(
            new EvalReport.CaseResult("r1", "in", "exp", "act", 0.95, true, "", 0L),
            new EvalReport.CaseResult("r2", "in", "exp", "act", 0.5, false, "", 0L)
        );
        EvalReport current = new EvalReport(2, 1, 1, 0.725, 100L, currentResults);

        RegressionDetector detector = new RegressionDetector();
        List<RegressionDetector.Regression> regressions = detector.detect(baseline, current, 0.051);

        assertThat(regressions).hasSize(1);
        assertThat(regressions.get(0).caseId()).isEqualTo("r2");
        assertThat(regressions.get(0).baselineScore()).isEqualTo(0.9);
        assertThat(regressions.get(0).currentScore()).isEqualTo(0.5);
        assertThat(regressions.get(0).delta()).isEqualTo(0.4);
    }

    @Test
    void testRunnerExceptionHandled() {
        List<Scorer> scorers = List.of(new ExactMatchScorer());
        EvalHarness harness = new EvalHarness(scorers, 1.0);

        List<EvalCase> cases = List.of(
            new EvalCase("err", "input", "expected", "rubric", List.of())
        );

        EvalReport report = harness.run(cases, c -> { throw new RuntimeException("boom"); });
        assertThat(report.totalCases()).isEqualTo(1);
        assertThat(report.passedCases()).isEqualTo(0);
        assertThat(report.perCaseResults().get(0).actualOutput()).startsWith("ERROR:");
    }
}
