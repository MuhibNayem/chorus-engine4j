package com.chorus.engine.eval;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Compares a current evaluation run against a baseline and flags regressions.
 */
public class RegressionDetector {

    private static final double DEFAULT_REGRESSION_THRESHOLD = 0.05;

    /**
     * Represents a detected regression for a single case.
     */
    public record Regression(
        String caseId,
        double baselineScore,
        double currentScore,
        double delta
    ) {}

    /**
     * Detects regressions by comparing per-case scores.
     * A regression is flagged when the current score drops more than
     * {@code threshold} below the baseline score.
     */
    public List<Regression> detect(EvalReport baseline, EvalReport current) {
        return detect(baseline, current, DEFAULT_REGRESSION_THRESHOLD);
    }

    public List<Regression> detect(EvalReport baseline, EvalReport current, double threshold) {
        List<Regression> regressions = new ArrayList<>();
        if (baseline == null || current == null) {
            return regressions;
        }

        Map<String, EvalReport.CaseResult> baselineById = baseline.perCaseResults().stream()
            .collect(Collectors.toMap(EvalReport.CaseResult::caseId, r -> r));

        for (EvalReport.CaseResult currentResult : current.perCaseResults()) {
            EvalReport.CaseResult baselineResult = baselineById.get(currentResult.caseId());
            if (baselineResult == null) {
                continue;
            }
            double delta = baselineResult.score() - currentResult.score();
            if (delta > threshold) {
                regressions.add(new Regression(
                    currentResult.caseId(),
                    baselineResult.score(),
                    currentResult.score(),
                    delta
                ));
            }
        }

        return regressions;
    }
}
