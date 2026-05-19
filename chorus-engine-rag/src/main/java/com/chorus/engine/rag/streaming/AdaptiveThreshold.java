package com.chorus.engine.rag.streaming;

import org.jspecify.annotations.NonNull;

import java.time.Duration;

/**
 * Threshold configuration for the {@link GenerationStrategy#ADAPTIVE} strategy.
 *
 * <p>Generation starts when <strong>any</strong> of the following conditions
 * is satisfied:
 * <ol>
 *   <li>At least {@code minWaves} have completed</li>
 *   <li>Cumulative relevance score of accumulated chunks ≥ {@code minRelevanceScore}</li>
 *   <li>{@code maxWaitTime} has elapsed since the first wave started</li>
 * </ol>
 */
public record AdaptiveThreshold(
    int minWaves,
    double minRelevanceScore,
    @NonNull Duration maxWaitTime
) {
    public AdaptiveThreshold {
        if (minWaves < 1) {
            throw new IllegalArgumentException("minWaves must be >= 1");
        }
        if (minRelevanceScore < 0.0 || minRelevanceScore > 1.0) {
            throw new IllegalArgumentException("minRelevanceScore must be in [0.0, 1.0]");
        }
    }

    public static @NonNull AdaptiveThreshold defaults() {
        return new AdaptiveThreshold(2, 0.65, Duration.ofMillis(300));
    }

    public static @NonNull AdaptiveThreshold fast() {
        return new AdaptiveThreshold(1, 0.40, Duration.ofMillis(150));
    }

    public static @NonNull AdaptiveThreshold quality() {
        return new AdaptiveThreshold(3, 0.80, Duration.ofMillis(800));
    }
}
