package com.chorus.engine.telemetry;

import com.chorus.engine.telemetry.metrics.CostTracker;
import com.chorus.engine.telemetry.metrics.PricingTable;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.*;

class CostTrackerTest {

    private final PricingTable pricing = PricingTable.defaults();

    @Test
    void singleCallCostCalculation() {
        CostTracker tracker = new CostTracker(pricing);
        tracker.recordLlmCall("run-1", "gpt-4o", 1000, 500);

        CostTracker.CostReport report = tracker.getReport("run-1");
        assertThat(report.runId()).isEqualTo("run-1");
        assertThat(report.totalCost()).isGreaterThan(BigDecimal.ZERO);

        // gpt-4o: input $0.005/1K, output $0.015/1K
        // 1000 input -> $0.005, 500 output -> $0.0075, total -> $0.0125
        assertThat(report.totalCost()).isEqualByComparingTo(new BigDecimal("0.0125"));
    }

    @Test
    void multipleCallsAccumulate() {
        CostTracker tracker = new CostTracker(pricing);
        tracker.recordLlmCall("run-1", "gpt-4o", 1000, 0);
        tracker.recordLlmCall("run-1", "gpt-4o", 0, 1000);

        CostTracker.CostReport report = tracker.getReport("run-1");
        assertThat(report.totalCost()).isEqualByComparingTo(new BigDecimal("0.0200"));
    }

    @Test
    void unknownModelReturnsZero() {
        CostTracker tracker = new CostTracker(pricing);
        tracker.recordLlmCall("run-1", "unknown-model", 1000, 1000);

        CostTracker.CostReport report = tracker.getReport("run-1");
        assertThat(report.totalCost()).isEqualTo(BigDecimal.ZERO);
    }

    @Test
    void perModelBreakdown() {
        CostTracker tracker = new CostTracker(pricing);
        tracker.recordLlmCall("run-1", "gpt-4o", 1000, 0);
        tracker.recordLlmCall("run-1", "gpt-4o-mini", 1000, 0);

        CostTracker.CostReport report = tracker.getReport("run-1");
        assertThat(report.breakdown()).hasSize(2);
        assertThat(report.breakdown()).containsKey("gpt-4o");
        assertThat(report.breakdown()).containsKey("gpt-4o-mini");
    }

    @Test
    void differentRunsAreIsolated() {
        CostTracker tracker = new CostTracker(pricing);
        tracker.recordLlmCall("run-a", "gpt-4o", 1000, 0);
        tracker.recordLlmCall("run-b", "gpt-4o", 2000, 0);

        assertThat(tracker.getReport("run-a").totalCost()).isEqualByComparingTo(new BigDecimal("0.0050"));
        assertThat(tracker.getReport("run-b").totalCost()).isEqualByComparingTo(new BigDecimal("0.0100"));
    }

    @Test
    void emptyReportForUnknownRun() {
        CostTracker tracker = new CostTracker(pricing);
        CostTracker.CostReport report = tracker.getReport("nonexistent");
        assertThat(report.totalCost()).isEqualTo(BigDecimal.ZERO);
        assertThat(report.breakdown()).isEmpty();
    }
}
