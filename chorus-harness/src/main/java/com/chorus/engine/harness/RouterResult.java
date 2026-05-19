package com.chorus.engine.harness;

/**
 * Result of a semantic routing decision, including the matched route, confidence score,
 * and the specific example utterance that produced the highest similarity.
 */
public record RouterResult(
    String routeName,
    double confidence,
    String matchedExample
) {

    public RouterResult {
        if (routeName == null || routeName.isBlank()) {
            throw new IllegalArgumentException("Route name must not be blank");
        }
        if (confidence < 0.0 || confidence > 1.0) {
            throw new IllegalArgumentException("Confidence must be between 0.0 and 1.0");
        }
        if (matchedExample == null) {
            matchedExample = "";
        }
    }
}
