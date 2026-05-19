package com.chorus.engine.telemetry.metrics;

import org.jspecify.annotations.NonNull;

import java.math.BigDecimal;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Tracks per-run LLM cost attribution.
 * <p>
 * Thread-safe. Uses {@link BigDecimal} for financial precision.
 */
public final class CostTracker {

    private final PricingTable pricingTable;
    private final ConcurrentHashMap<String, CostReport> reports = new ConcurrentHashMap<>();

    public CostTracker(@NonNull PricingTable pricingTable) {
        this.pricingTable = Objects.requireNonNull(pricingTable, "pricingTable cannot be null");
    }

    /**
     * Record an LLM call against a run.
     */
    public void recordLlmCall(@NonNull String runId, @NonNull String model, int inputTokens, int outputTokens) {
        BigDecimal cost = pricingTable.calculateCost(model, inputTokens, outputTokens);
        reports.computeIfAbsent(runId, CostReport::new)
               .addModelCost(model, inputTokens, outputTokens, cost);
    }

    /**
     * Get the current cost report for a run.
     * Returns an empty report if no data exists.
     */
    public @NonNull CostReport getReport(@NonNull String runId) {
        return reports.getOrDefault(runId, new CostReport(runId));
    }

    /**
     * Immutable snapshot of costs for a single run.
     */
    public static final class CostReport {
        private final String runId;
        private final ConcurrentHashMap<String, ModelCost> breakdown = new ConcurrentHashMap<>();
        private final AtomicReference<BigDecimal> totalCost = new AtomicReference<>(BigDecimal.ZERO);

        private CostReport(@NonNull String runId) {
            this.runId = runId;
        }

        void addModelCost(@NonNull String model, int inputTokens, int outputTokens, @NonNull BigDecimal cost) {
            breakdown.merge(model,
                new ModelCost(model, inputTokens, outputTokens, cost),
                ModelCost::merge);
            totalCost.updateAndGet(current -> current.add(cost));
        }

        public @NonNull String runId() {
            return runId;
        }

        public @NonNull BigDecimal totalCost() {
            return totalCost.get();
        }

        public @NonNull Map<String, ModelCost> breakdown() {
            return Map.copyOf(breakdown);
        }

        /**
         * Cost attributed to a single model within a run.
         */
        public record ModelCost(
            @NonNull String model,
            int inputTokens,
            int outputTokens,
            @NonNull BigDecimal cost
        ) {
            static @NonNull ModelCost merge(@NonNull ModelCost a, @NonNull ModelCost b) {
                return new ModelCost(
                    a.model,
                    a.inputTokens + b.inputTokens,
                    a.outputTokens + b.outputTokens,
                    a.cost.add(b.cost)
                );
            }
        }
    }
}
