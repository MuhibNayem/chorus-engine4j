package com.chorus.engine.core.cost;

/**
 * Pricing information for an LLM model.
 */
public record ModelPricing(String model, double inputPricePer1k, double outputPricePer1k) {
}
