package com.chorus.engine.telemetry.metrics;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.*;

class BudgetEnforcerTest {

    @Test
    void constructionWithValidBudget() {
        BigDecimal limit = new BigDecimal("100.50");
        BudgetEnforcer enforcer = new BudgetEnforcer(limit);
        assertThat(enforcer.budgetLimit()).isEqualByComparingTo(limit);
    }

    @Test
    void constructionWithZeroBudget() {
        BudgetEnforcer enforcer = new BudgetEnforcer(BigDecimal.ZERO);
        assertThat(enforcer.budgetLimit()).isEqualByComparingTo(BigDecimal.ZERO);
    }

    @Test
    void constructionWithNegativeBudgetThrows() {
        assertThatThrownBy(() -> new BudgetEnforcer(new BigDecimal("-1.00")))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("budgetLimit must be >= 0");
    }

    @Test
    void constructionWithNullBudgetThrows() {
        assertThatThrownBy(() -> new BudgetEnforcer(null))
            .isInstanceOf(NullPointerException.class)
            .hasMessageContaining("budgetLimit cannot be null");
    }

    @Test
    void recordSpendAccumulates() {
        BudgetEnforcer enforcer = new BudgetEnforcer(new BigDecimal("100"));
        BigDecimal cost1 = new BigDecimal("30");
        BigDecimal cost2 = new BigDecimal("40");
        BigDecimal total = cost1.add(cost2);

        enforcer.checkBudget(cost1);
        enforcer.checkBudget(total);

        assertThat(total).isEqualByComparingTo(new BigDecimal("70"));
    }

    @Test
    void checkBudgetThrowsWhenExceeded() {
        BudgetEnforcer enforcer = new BudgetEnforcer(new BigDecimal("50"));
        BigDecimal over = new BigDecimal("51");

        assertThatThrownBy(() -> enforcer.checkBudget(over))
            .isInstanceOf(BudgetEnforcer.BudgetExceededException.class)
            .hasMessageContaining("Budget exceeded: 51 > 50");
    }

    @Test
    void checkBudgetExactlyAtLimitDoesNotThrow() {
        BudgetEnforcer enforcer = new BudgetEnforcer(new BigDecimal("50"));
        assertThatNoException().isThrownBy(() -> enforcer.checkBudget(new BigDecimal("50")));
    }

    @Test
    void checkBudgetJustUnderLimitDoesNotThrow() {
        BudgetEnforcer enforcer = new BudgetEnforcer(new BigDecimal("50"));
        assertThatNoException().isThrownBy(() -> enforcer.checkBudget(new BigDecimal("49.99")));
    }

    @Test
    void checkBudgetWithNullThrows() {
        BudgetEnforcer enforcer = new BudgetEnforcer(new BigDecimal("50"));
        assertThatThrownBy(() -> enforcer.checkBudget(null))
            .isInstanceOf(NullPointerException.class)
            .hasMessageContaining("currentCost cannot be null");
    }
}
