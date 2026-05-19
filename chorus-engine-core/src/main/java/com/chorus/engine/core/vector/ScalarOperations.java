package com.chorus.engine.core.vector;

import org.jspecify.annotations.NonNull;

/**
 * Pure scalar fallback vector operations. Always works, no special CPU features required.
 */
final class ScalarOperations implements VectorOperations {

    @Override
    public double dotProduct(float[] a, float[] b) {
        if (a.length != b.length) throw new IllegalArgumentException("Dimension mismatch");
        double sum = 0.0;
        for (int i = 0; i < a.length; i++) sum += a[i] * b[i];
        return sum;
    }

    @Override
    public double cosineSimilarity(float[] a, float[] b) {
        if (a.length != b.length) return -1.0;
        if (a.length == 0) return 0.0;
        double dot = 0.0, na = 0.0, nb = 0.0;
        for (int i = 0; i < a.length; i++) {
            dot += a[i] * b[i];
            na += a[i] * a[i];
            nb += b[i] * b[i];
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
            sum += diff * diff;
        }
        return sum;
    }

    @Override
    public void normalize(float[] vec) {
        double sum = 0.0;
        for (float v : vec) sum += v * v;
        if (sum == 0.0) return;
        double inv = 1.0 / Math.sqrt(sum);
        for (int i = 0; i < vec.length; i++) vec[i] *= inv;
    }

    @Override
    public @NonNull String implementationName() { return "Scalar"; }
}
