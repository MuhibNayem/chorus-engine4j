package com.chorus.engine.telemetry.metrics;

import org.jspecify.annotations.NonNull;

import java.math.BigDecimal;
import java.util.Objects;

/**
 * Enforces a maximum budget per run or session.
 * Throws {@link BudgetExceededException} when the limit is crossed.
 */
public final class BudgetEnforcer {

    private final BigDecimal budgetLimit;

    public BudgetEnforcer(@NonNull BigDecimal budgetLimit) {
        Objects.requireNonNull(budgetLimit, "budgetLimit cannot be null");
        if (budgetLimit.compareTo(BigDecimal.ZERO) < 0) {
            throw new IllegalArgumentException("budgetLimit must be >= 0");
        }
        this.budgetLimit = budgetLimit;
    }

    /**
     * Check whether the current cost is within budget.
     *
     * @throws BudgetExceededException if currentCost &gt; budgetLimit
     */
    public void checkBudget(@NonNull BigDecimal currentCost) {
        Objects.requireNonNull(currentCost, "currentCost cannot be null");
        if (currentCost.compareTo(budgetLimit) > 0) {
            throw new BudgetExceededException(
                "Budget exceeded: " + currentCost + " > " + budgetLimit);
        }
    }

    public @NonNull BigDecimal budgetLimit() {
        return budgetLimit;
    }

    public static final class BudgetExceededException extends RuntimeException {
        public BudgetExceededException(@NonNull String message) {
            super(Objects.requireNonNull(message));
        }
    }
}
