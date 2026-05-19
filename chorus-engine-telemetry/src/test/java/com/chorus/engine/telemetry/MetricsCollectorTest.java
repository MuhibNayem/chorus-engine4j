package com.chorus.engine.telemetry;

import com.chorus.engine.telemetry.metrics.MetricsCollector;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.*;

class MetricsCollectorTest {

    @Test
    void countersIncrement() {
        MetricsCollector metrics = new MetricsCollector();
        metrics.recordRun();
        metrics.recordRun();
        metrics.recordToolCall();
        metrics.recordError();
        metrics.recordRagQuery();

        MetricsCollector.Snapshot s = metrics.snapshot();
        assertThat(s.totalRuns()).isEqualTo(2);
        assertThat(s.totalToolCalls()).isEqualTo(1);
        assertThat(s.totalErrors()).isEqualTo(1);
        assertThat(s.totalRagQueries()).isEqualTo(1);
    }

    @Test
    void histogramPercentiles() {
        MetricsCollector metrics = new MetricsCollector();
        metrics.recordLatency(Duration.ofMillis(10));
        metrics.recordLatency(Duration.ofMillis(20));
        metrics.recordLatency(Duration.ofMillis(30));
        metrics.recordLatency(Duration.ofMillis(40));
        metrics.recordLatency(Duration.ofMillis(50));

        MetricsCollector.Snapshot s = metrics.snapshot();
        assertThat(s.latencyP50()).isEqualTo(30);
        assertThat(s.latencyP95()).isEqualTo(50);
        assertThat(s.latencyP99()).isEqualTo(50);
    }

    @Test
    void tokenHistogramPercentiles() {
        MetricsCollector metrics = new MetricsCollector();
        metrics.recordTokens(100);
        metrics.recordTokens(200);
        metrics.recordTokens(300);

        MetricsCollector.Snapshot s = metrics.snapshot();
        assertThat(s.tokenP50()).isEqualTo(200);
    }

    @Test
    void gauges() {
        MetricsCollector metrics = new MetricsCollector();
        metrics.setActiveSessions(5);
        metrics.setCircuitBreakerState("agent-1", 1);
        metrics.setCircuitBreakerState("agent-2", 2);

        MetricsCollector.Snapshot s = metrics.snapshot();
        assertThat(s.activeSessions()).isEqualTo(5);
        assertThat(s.circuitBreakerStates()).containsEntry("agent-1", 1).containsEntry("agent-2", 2);
    }

    @Test
    void emptyHistogramReturnsZero() {
        MetricsCollector metrics = new MetricsCollector();
        MetricsCollector.Snapshot s = metrics.snapshot();
        assertThat(s.latencyP50()).isEqualTo(0);
        assertThat(s.latencyP95()).isEqualTo(0);
        assertThat(s.latencyP99()).isEqualTo(0);
    }

    @Test
    void histogramBounded() {
        MetricsCollector metrics = new MetricsCollector(3);
        metrics.recordLatency(Duration.ofMillis(10));
        metrics.recordLatency(Duration.ofMillis(20));
        metrics.recordLatency(Duration.ofMillis(30));
        metrics.recordLatency(Duration.ofMillis(40));

        MetricsCollector.Snapshot s = metrics.snapshot();
        // Oldest value (10) should have been evicted
        assertThat(s.latencyP50()).isEqualTo(30);
    }
}
