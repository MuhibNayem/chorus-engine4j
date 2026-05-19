package com.chorus.engine.guardrails;

import org.jspecify.annotations.NonNull;

import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Self-improving threshold for guardrail confidence scores.
 * Adjusts based on operator feedback (false positives / false negatives).
 *
 * <p>Thread-safe. Uses exponential moving average for stability.
 */
public final class AdaptiveThreshold {

    private final AtomicReference<Double> threshold;
    private final double learningRate;
    private final double minThreshold;
    private final double maxThreshold;
    private final AtomicLong falsePositives = new AtomicLong(0);
    private final AtomicLong falseNegatives = new AtomicLong(0);
    private final AtomicLong truePositives = new AtomicLong(0);
    private final AtomicLong trueNegatives = new AtomicLong(0);

    public AdaptiveThreshold(double initialThreshold, double learningRate, double minThreshold, double maxThreshold) {
        if (initialThreshold < 0 || initialThreshold > 1) throw new IllegalArgumentException();
        if (learningRate <= 0 || learningRate > 1) throw new IllegalArgumentException();
        this.threshold = new AtomicReference<>(initialThreshold);
        this.learningRate = learningRate;
        this.minThreshold = minThreshold;
        this.maxThreshold = maxThreshold;
    }

    public double currentThreshold() {
        return threshold.get();
    }

    /**
     * Record feedback. True positive = correctly blocked. False positive = incorrectly blocked.
     */
    public void recordTruePositive() {
        truePositives.incrementAndGet();
    }

    public void recordTrueNegative() {
        trueNegatives.incrementAndGet();
    }

    public void recordFalsePositive() {
        falsePositives.incrementAndGet();
        // False positive → threshold was too low, raise it
        adjust(+1);
    }

    public void recordFalseNegative() {
        falseNegatives.incrementAndGet();
        // False negative → threshold was too high, lower it
        adjust(-1);
    }

    private void adjust(int direction) {
        threshold.updateAndGet(t -> {
            double adjusted = t + (direction * learningRate * (1 - t) * t); // sigmoid-like adjustment
            return Math.max(minThreshold, Math.min(maxThreshold, adjusted));
        });
    }

    public @NonNull Metrics metrics() {
        long fp = falsePositives.get();
        long fn = falseNegatives.get();
        long tp = truePositives.get();
        long tn = trueNegatives.get();
        long total = tp + tn + fp + fn;
        double accuracy = total > 0 ? (double) (tp + tn) / total : 0.0;
        double precision = (tp + fp) > 0 ? (double) tp / (tp + fp) : 0.0;
        double recall = (tp + fn) > 0 ? (double) tp / (tp + fn) : 0.0;
        return new Metrics(threshold.get(), tp, tn, fp, fn, accuracy, precision, recall);
    }

    public record Metrics(
        double currentThreshold,
        long truePositives,
        long trueNegatives,
        long falsePositives,
        long falseNegatives,
        double accuracy,
        double precision,
        double recall
    ) {}
}
