package com.chorus.engine.telemetry.metrics;

import org.jspecify.annotations.NonNull;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Immutable pricing table mapping model names to per-1K-token prices.
 */
public final class PricingTable {

    private final Map<String, ModelPricing> pricingMap;

    public PricingTable(@NonNull Map<String, ModelPricing> pricingMap) {
        this.pricingMap = Map.copyOf(pricingMap);
    }

    /**
     * Calculate the cost for a given model and token counts.
     * Returns {@link BigDecimal#ZERO} if the model is unknown.
     */
    public @NonNull BigDecimal calculateCost(@NonNull String model, int inputTokens, int outputTokens) {
        ModelPricing pricing = pricingMap.get(model);
        if (pricing == null) {
            return BigDecimal.ZERO;
        }
        BigDecimal inputCost = pricing.inputPricePer1k()
            .multiply(BigDecimal.valueOf(inputTokens))
            .divide(BigDecimal.valueOf(1000), 10, RoundingMode.HALF_UP);
        BigDecimal outputCost = pricing.outputPricePer1k()
            .multiply(BigDecimal.valueOf(outputTokens))
            .divide(BigDecimal.valueOf(1000), 10, RoundingMode.HALF_UP);
        return inputCost.add(outputCost).stripTrailingZeros();
    }

    /**
     * Default pricing for common models (USD per 1K tokens).
     */
    public static @NonNull PricingTable defaults() {
        Map<String, ModelPricing> map = new HashMap<>();
        map.put("gpt-4o", new ModelPricing(new BigDecimal("0.005"), new BigDecimal("0.015")));
        map.put("gpt-4o-mini", new ModelPricing(new BigDecimal("0.00015"), new BigDecimal("0.00060")));
        map.put("claude-3-5-sonnet", new ModelPricing(new BigDecimal("0.003"), new BigDecimal("0.015")));
        map.put("claude-3-haiku", new ModelPricing(new BigDecimal("0.00025"), new BigDecimal("0.00125")));
        return new PricingTable(map);
    }

    /**
     * Price configuration for a single model.
     */
    public record ModelPricing(
        @NonNull BigDecimal inputPricePer1k,
        @NonNull BigDecimal outputPricePer1k
    ) {
        public ModelPricing {
            Objects.requireNonNull(inputPricePer1k, "inputPricePer1k cannot be null");
            Objects.requireNonNull(outputPricePer1k, "outputPricePer1k cannot be null");
            if (inputPricePer1k.compareTo(BigDecimal.ZERO) < 0) {
                throw new IllegalArgumentException("inputPricePer1k must be >= 0");
            }
            if (outputPricePer1k.compareTo(BigDecimal.ZERO) < 0) {
                throw new IllegalArgumentException("outputPricePer1k must be >= 0");
            }
        }
    }
}
