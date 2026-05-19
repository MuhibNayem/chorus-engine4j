package com.chorus.engine.telemetry;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Tracks per-run and per-thread cost estimates for LLM token usage.
 * Cost = inputTokens * inputPrice + outputTokens * outputPrice.
 */
public class CostTracker {

    private final Map<String, AtomicReference<ThreadCost>> threadCosts = new ConcurrentHashMap<>();
    private final Map<String, AtomicReference<RunCost>> runCosts = new ConcurrentHashMap<>();
    private final Pricing defaultPricing;

    public record Pricing(double inputPricePerToken, double outputPricePerToken) {
        public Pricing {
            if (inputPricePerToken < 0) throw new IllegalArgumentException("inputPrice must be >= 0");
            if (outputPricePerToken < 0) throw new IllegalArgumentException("outputPrice must be >= 0");
        }
    }

    public record ThreadCost(int inputTokens, int outputTokens, double totalCost) {}
    public record RunCost(int inputTokens, int outputTokens, double totalCost) {}

    public CostTracker(Pricing defaultPricing) {
        this.defaultPricing = defaultPricing;
    }

    public CostTracker() {
        this(new Pricing(0.0, 0.0));
    }

    /**
     * Record token usage against a thread, using default pricing.
     */
    public void recordThreadTokens(String threadId, int inputTokens, int outputTokens) {
        recordThreadTokens(threadId, inputTokens, outputTokens, defaultPricing);
    }

    /**
     * Record token usage against a thread with custom pricing.
     */
    public void recordThreadTokens(String threadId, int inputTokens, int outputTokens, Pricing pricing) {
        threadCosts.computeIfAbsent(threadId, k -> new AtomicReference<>(new ThreadCost(0, 0, 0.0)))
            .updateAndGet(prev -> {
                int newInput = prev.inputTokens() + inputTokens;
                int newOutput = prev.outputTokens() + outputTokens;
                double added = inputTokens * pricing.inputPricePerToken() + outputTokens * pricing.outputPricePerToken();
                return new ThreadCost(newInput, newOutput, prev.totalCost() + added);
            });
    }

    /**
     * Record token usage against a run with custom pricing.
     */
    public void recordRunCost(String runId, int inputTokens, int outputTokens, Pricing pricing) {
        runCosts.computeIfAbsent(runId, k -> new AtomicReference<>(new RunCost(0, 0, 0.0)))
            .updateAndGet(prev -> {
                int newInput = prev.inputTokens() + inputTokens;
                int newOutput = prev.outputTokens() + outputTokens;
                double added = inputTokens * pricing.inputPricePerToken() + outputTokens * pricing.outputPricePerToken();
                return new RunCost(newInput, newOutput, prev.totalCost() + added);
            });
    }

    public ThreadCost getThreadCost(String threadId) {
        AtomicReference<ThreadCost> ref = threadCosts.get(threadId);
        return ref != null ? ref.get() : new ThreadCost(0, 0, 0.0);
    }

    public RunCost getRunCost(String runId) {
        AtomicReference<RunCost> ref = runCosts.get(runId);
        return ref != null ? ref.get() : new RunCost(0, 0, 0.0);
    }

    public void resetThread(String threadId) {
        threadCosts.remove(threadId);
    }

    public void resetRun(String runId) {
        runCosts.remove(runId);
    }

    public Map<String, ThreadCost> snapshotThreadCosts() {
        return threadCosts.entrySet().stream()
            .collect(ConcurrentHashMap::new,
                (m, e) -> m.put(e.getKey(), e.getValue().get()),
                ConcurrentHashMap::putAll);
    }

    public Map<String, RunCost> snapshotRunCosts() {
        return runCosts.entrySet().stream()
            .collect(ConcurrentHashMap::new,
                (m, e) -> m.put(e.getKey(), e.getValue().get()),
                ConcurrentHashMap::putAll);
    }
}
