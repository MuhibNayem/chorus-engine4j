package com.chorus.engine.eval;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.assertj.core.api.AbstractAssert;
import org.assertj.core.description.TextDescription;

import java.io.IOException;
import java.io.InputStream;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/**
 * Core evaluation engine.
 * <p>
 * Loads datasets, runs each case through a runner, scores results,
 * and produces an {@link EvalReport}.
 */
public class EvalHarness {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final List<Scorer> scorers;
    private final double passThreshold;

    public EvalHarness(List<Scorer> scorers) {
        this(scorers, 0.8);
    }

    public EvalHarness(List<Scorer> scorers, double passThreshold) {
        this.scorers = List.copyOf(scorers);
        this.passThreshold = passThreshold;
    }

    /**
     * Runs all cases and returns an aggregate report.
     *
     * @param cases  the test cases to evaluate
     * @param runner function that produces the actual output for a given case
     */
    public EvalReport run(List<EvalCase> cases, Function<EvalCase, String> runner) {
        Instant start = Instant.now();
        List<EvalReport.CaseResult> results = new ArrayList<>();
        int passed = 0;
        double totalScore = 0.0;

        for (EvalCase c : cases) {
            Instant caseStart = Instant.now();
            String actual;
            try {
                actual = runner.apply(c);
            } catch (Exception e) {
                actual = "ERROR: " + e.getMessage();
            }
            long caseDuration = Duration.between(caseStart, Instant.now()).toMillis();

            double score = computeScore(c, actual);
            boolean isPass = score >= passThreshold;
            if (isPass) {
                passed++;
            }
            totalScore += score;

            String message = isPass ? "PASS" : "FAIL (score=" + String.format("%.3f", score) + ")";
            results.add(new EvalReport.CaseResult(
                c.id(),
                c.input(),
                c.expectedOutput(),
                actual,
                score,
                isPass,
                message,
                caseDuration
            ));
        }

        long duration = Duration.between(start, Instant.now()).toMillis();
        int total = cases.size();
        double average = total > 0 ? totalScore / total : 0.0;

        return new EvalReport(total, passed, total - passed, average, duration, results);
    }

    private double computeScore(EvalCase c, String actual) {
        if (scorers.isEmpty()) {
            return 0.0;
        }
        double sum = 0.0;
        for (Scorer scorer : scorers) {
            String reference = (scorer instanceof LlmAsJudgeScorer) ? c.rubric() : c.expectedOutput();
            sum += scorer.score(reference, actual);
        }
        return sum / scorers.size();
    }

    /**
     * Loads a dataset from the classpath.
     *
     * @param resourcePath classpath resource path (e.g. "evals/sample-dataset.json")
     */
    public static List<EvalCase> loadDataset(String resourcePath) {
        try (InputStream is = EvalHarness.class.getClassLoader().getResourceAsStream(resourcePath)) {
            if (is == null) {
                throw new IllegalArgumentException("Dataset not found on classpath: " + resourcePath);
            }
            List<Map<String, Object>> raw = MAPPER.readValue(is, new TypeReference<>() {});
            List<EvalCase> cases = new ArrayList<>();
            for (Map<String, Object> item : raw) {
                String id = (String) item.get("id");
                String input = (String) item.get("input");
                String expected = (String) item.get("expectedOutput");
                String rubric = (String) item.get("rubric");
                @SuppressWarnings("unchecked")
                List<String> tags = (List<String>) item.get("tags");
                cases.add(new EvalCase(id, input, expected, rubric, tags));
            }
            return cases;
        } catch (IOException e) {
            throw new RuntimeException("Failed to load dataset: " + resourcePath, e);
        }
    }

    /**
     * AssertJ-style entry point for evaluation assertions.
     */
    public static EvalAssert assertThat(String actual) {
        return new EvalAssert(actual);
    }

    /**
     * AssertJ-style assertions for evaluation outputs.
     */
    public static class EvalAssert extends AbstractAssert<EvalAssert, String> {

        protected EvalAssert(String actual) {
            super(actual, EvalAssert.class);
        }

        /**
         * Asserts that the actual output exactly matches the expected output.
         */
        public EvalAssert matchesRubric(String expected) {
            isNotNull();
            if (!expected.equals(actual)) {
                failWithMessage("Expected output to match <%s> but was <%s>", expected, actual);
            }
            return this;
        }

        /**
         * Asserts that the actual output satisfies the given rubric using a scorer.
         */
        public EvalAssert matchesRubric(String expected, String rubric, Scorer scorer, double threshold) {
            isNotNull();
            double score = scorer.score(expected, actual);
            if (score < threshold) {
                failWithMessage(
                    "Expected output to satisfy rubric with score >= %s but was %s. Rubric: %s",
                    threshold, score, rubric
                );
            }
            return this;
        }

        /**
         * Asserts that the actual output contains the expected substring.
         */
        public EvalAssert containsExpected(String expected) {
            isNotNull();
            if (!actual.contains(expected)) {
                failWithMessage("Expected output to contain <%s> but was <%s>", expected, actual);
            }
            return this;
        }
    }
}
