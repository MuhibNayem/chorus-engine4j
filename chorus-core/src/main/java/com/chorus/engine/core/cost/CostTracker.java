package com.chorus.engine.core.cost;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Tracks per-thread and per-session LLM costs with budget enforcement.
 */
public class CostTracker {

    private static final Logger log = LoggerFactory.getLogger(CostTracker.class);

    private final Map<String, ModelPricing> pricingTable;
    private final Map<String, Double> threadCosts = new ConcurrentHashMap<>();
    private final Map<String, Double> sessionCosts = new ConcurrentHashMap<>();
    private final Map<String, Double> budgets = new ConcurrentHashMap<>();

    public CostTracker(Map<String, ModelPricing> pricingTable) {
        this.pricingTable = Map.copyOf(pricingTable);
    }

    public double estimateCost(String model, int inputTokens, int outputTokens) {
        ModelPricing pricing = pricingTable.getOrDefault(model, new ModelPricing(model, 0.0, 0.0));
        double inputCost = (inputTokens / 1000.0) * pricing.inputPricePer1k();
        double outputCost = (outputTokens / 1000.0) * pricing.outputPricePer1k();
        return inputCost + outputCost;
    }

    public void recordUsage(String threadId, String sessionId, String model, int inputTokens, int outputTokens) {
        double cost = estimateCost(model, inputTokens, outputTokens);
        threadCosts.merge(threadId, cost, Double::sum);
        sessionCosts.merge(sessionId, cost, Double::sum);

        checkBudget(threadId);
        checkBudget(sessionId);
    }

    public void setBudget(String id, double budgetUsd) {
        budgets.put(id, budgetUsd);
    }

    public double getCost(String id) {
        Double cost = threadCosts.get(id);
        if (cost != null) return cost;
        return sessionCosts.getOrDefault(id, 0.0);
    }

    private void checkBudget(String id) {
        Double budget = budgets.get(id);
        Double cost = getCost(id);
        if (budget != null && cost > budget) {
            log.error("Budget exceeded for {}: ${:.4f} / ${:.4f}", id, cost, budget);
            throw new BudgetExceededException("Budget exceeded for " + id + ": $" + cost + " / $" + budget);
        }
    }

    public static class BudgetExceededException extends RuntimeException {
        public BudgetExceededException(String message) {
            super(message);
        }
    }
}
