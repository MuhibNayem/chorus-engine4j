package com.chorus.engine.core.vector;

import org.jspecify.annotations.NonNull;

/**
 * Pluggable high-performance vector operations.
 *
 * <p>The framework never hardcodes dot products or cosine similarity.
 * Instead, it uses an implementation of this interface selected at runtime:
 * <ol>
 *   <li>{@link VectorApiOperations} — SIMD via Java Vector API (2-3x faster, JDK 16+)</li>
 *   <li>{@link FmaOperations} — Fused multiply-add scalar (1.6x faster than naive)</li>
 *   <li>{@link ScalarOperations} — pure scalar fallback (always works)</li>
 * </ol>
 *
 * <p>Users can inject their own implementation (e.g., JNI BLAS, GPU, custom SIMD).
 */
public interface VectorOperations {

    double dotProduct(float[] a, float[] b);

    double cosineSimilarity(float[] a, float[] b);

    double euclideanDistance(float[] a, float[] b);

    double squaredEuclideanDistance(float[] a, float[] b);

    void normalize(float[] vec);

    /** Name of this implementation for metrics/observability. */
    @NonNull String implementationName();

    /**
     * Auto-detect and return the fastest available implementation.
     */
    static @NonNull VectorOperations autoDetect() {
        return VectorOpsProvider.INSTANCE.get();
    }
}
