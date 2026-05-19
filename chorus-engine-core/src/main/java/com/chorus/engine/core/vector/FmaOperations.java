package com.chorus.engine.core.vector;

import org.jspecify.annotations.NonNull;

/**
 * Fused multiply-add scalar vector operations.
 * Uses {@link Math#fma(float,float,float)} for better precision and ~1.6x speedup
 * over naive scalar on FMA-capable CPUs.
 */
final class FmaOperations implements VectorOperations {

    @Override
    public double dotProduct(float[] a, float[] b) {
        if (a.length != b.length) throw new IllegalArgumentException("Dimension mismatch");
        double sum = 0.0;
        for (int i = 0; i < a.length; i++) sum = Math.fma(a[i], b[i], sum);
        return sum;
    }

    @Override
    public double cosineSimilarity(float[] a, float[] b) {
        if (a.length != b.length) return -1.0;
        if (a.length == 0) return 0.0;
        double dot = 0.0, na = 0.0, nb = 0.0;
        for (int i = 0; i < a.length; i++) {
            dot = Math.fma(a[i], b[i], dot);
            na = Math.fma(a[i], a[i], na);
            nb = Math.fma(b[i], b[i], nb);
        }
        return (na == 0.0 || nb == 0.0) ? 0.0 : dot / Math.sqrt(na * nb);
    }

    @Override
    public double euclideanDistance(float[] a, float[] b) {
        return Math.sqrt(squaredEuclideanDistance(a, b));
    }

    @Override
    public double squaredEuclideanDistance(float[] a, float[] b) {
        if (a.length != b.length) throw new IllegalArgumentException("Dimension mismatch");
        double sum = 0.0;
        for (int i = 0; i < a.length; i++) {
            float diff = a[i] - b[i];
            sum = Math.fma(diff, diff, sum);
        }
        return sum;
    }

    @Override
    public void normalize(float[] vec) {
        double sum = 0.0;
        for (float v : vec) sum = Math.fma(v, v, sum);
        if (sum == 0.0) return;
        double inv = 1.0 / Math.sqrt(sum);
        for (int i = 0; i < vec.length; i++) vec[i] *= inv;
    }

    @Override
    public @NonNull String implementationName() { return "FMA-Scalar"; }
}
