package com.chorus.engine.telemetry.metrics;

import org.jspecify.annotations.NonNull;

import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.LongAdder;

/**
 * Thread-safe in-memory metrics collector for dashboards and health checks.
 * <p>
 * Maintains counters, latency histograms, token histograms, and gauges.
 * Histograms are bounded to the last {@value #DEFAULT_MAX_HISTOGRAM_SIZE} values.
 */
public final class MetricsCollector {

    private static final int DEFAULT_MAX_HISTOGRAM_SIZE = 10_000;

    private final LongAdder totalRuns = new LongAdder();
    private final LongAdder totalToolCalls = new LongAdder();
    private final LongAdder totalErrors = new LongAdder();
    private final LongAdder totalRagQueries = new LongAdder();

    private final BoundedHistogram latencyHistogram;
    private final BoundedHistogram tokenHistogram;

    private final AtomicInteger activeSessions = new AtomicInteger(0);
    private final ConcurrentHashMap<String, Integer> circuitBreakerStates = new ConcurrentHashMap<>();

    public MetricsCollector() {
        this(DEFAULT_MAX_HISTOGRAM_SIZE);
    }

    public MetricsCollector(int maxHistogramSize) {
        this.latencyHistogram = new BoundedHistogram(maxHistogramSize);
        this.tokenHistogram = new BoundedHistogram(maxHistogramSize);
    }

    // ---- Counters ----

    public void recordRun() {
        totalRuns.increment();
    }

    public void recordToolCall() {
        totalToolCalls.increment();
    }

    public void recordError() {
        totalErrors.increment();
    }

    public void recordRagQuery() {
        totalRagQueries.increment();
    }

    // ---- Histograms ----

    public void recordLatency(@NonNull Duration latency) {
        latencyHistogram.record(latency.toMillis());
    }

    public void recordTokens(int tokens) {
        tokenHistogram.record(tokens);
    }

    // ---- Gauges ----

    public void setActiveSessions(int count) {
        activeSessions.set(count);
    }

    public void setCircuitBreakerState(@NonNull String agentId, int state) {
        circuitBreakerStates.put(agentId, state);
    }

    public void removeCircuitBreakerState(@NonNull String agentId) {
        circuitBreakerStates.remove(agentId);
    }

    // ---- Snapshot ----

    public @NonNull Snapshot snapshot() {
        return new Snapshot(
            totalRuns.sum(),
            totalToolCalls.sum(),
            totalErrors.sum(),
            totalRagQueries.sum(),
            latencyHistogram.p50(),
            latencyHistogram.p95(),
            latencyHistogram.p99(),
            tokenHistogram.p50(),
            tokenHistogram.p95(),
            tokenHistogram.p99(),
            activeSessions.get(),
            Map.copyOf(circuitBreakerStates)
        );
    }

    /**
     * Immutable snapshot of all metrics at a point in time.
     */
    public record Snapshot(
        long totalRuns,
        long totalToolCalls,
        long totalErrors,
        long totalRagQueries,
        long latencyP50,
        long latencyP95,
        long latencyP99,
        long tokenP50,
        long tokenP95,
        long tokenP99,
        int activeSessions,
        @NonNull Map<String, Integer> circuitBreakerStates
    ) {}

    /**
     * Simple bounded histogram backed by a synchronized ArrayDeque.
     */
    private static final class BoundedHistogram {
        private final int maxSize;
        private final ArrayDeque<Long> values = new ArrayDeque<>();
        private final Object lock = new Object();

        BoundedHistogram(int maxSize) {
            this.maxSize = maxSize;
        }

        void record(long value) {
            synchronized (lock) {
                values.addLast(value);
                if (values.size() > maxSize) {
                    values.removeFirst();
                }
            }
        }

        long p50() {
            return percentile(0.50);
        }

        long p95() {
            return percentile(0.95);
        }

        long p99() {
            return percentile(0.99);
        }

        private long percentile(double p) {
            synchronized (lock) {
                if (values.isEmpty()) {
                    return 0L;
                }
                List<Long> sorted = new ArrayList<>(values);
                Collections.sort(sorted);
                int index = (int) Math.ceil(p * sorted.size()) - 1;
                return sorted.get(Math.max(0, index));
            }
        }
    }
}
