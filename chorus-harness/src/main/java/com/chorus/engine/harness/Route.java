package com.chorus.engine.harness;

import java.util.List;

/**
 * Represents a semantic routing target with example utterances and a similarity threshold.
 * Embeddings may be pre-cached to avoid redundant model calls.
 */
public record Route(
    String name,
    String targetAgent,
    List<String> exampleUtterances,
    double threshold,
    List<List<Double>> cachedEmbeddings
) {

    public Route {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("Route name must not be blank");
        }
        if (exampleUtterances == null) {
            exampleUtterances = List.of();
        }
        if (cachedEmbeddings == null) {
            cachedEmbeddings = List.of();
        }
        if (threshold < 0.0 || threshold > 1.0) {
            throw new IllegalArgumentException("Threshold must be between 0.0 and 1.0");
        }
    }

    /**
     * Convenience factory that starts with empty cached embeddings.
     */
    public static Route of(String name, String targetAgent, List<String> exampleUtterances, double threshold) {
        return new Route(name, targetAgent, exampleUtterances, threshold, List.of());
    }
}
