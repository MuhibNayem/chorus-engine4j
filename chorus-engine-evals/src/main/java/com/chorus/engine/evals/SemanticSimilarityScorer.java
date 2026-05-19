package com.chorus.engine.evals;

import com.chorus.engine.core.result.Result;
import com.chorus.engine.llm.embed.EmbeddingClient;
import org.jspecify.annotations.NonNull;

import java.util.Objects;

/**
 * Scorer that uses cosine similarity of embeddings to judge correctness.
 * Scores in [0, 1] based on similarity threshold.
 */
public final class SemanticSimilarityScorer implements EvalScorer {

    private final EmbeddingClient embeddingClient;
    private final double threshold;
    private final String model;

    public SemanticSimilarityScorer(
        @NonNull EmbeddingClient embeddingClient,
        double threshold,
        @NonNull String model
    ) {
        this.embeddingClient = Objects.requireNonNull(embeddingClient);
        if (threshold < 0 || threshold > 1) {
            throw new IllegalArgumentException("threshold must be in [0, 1]");
        }
        this.threshold = threshold;
        this.model = Objects.requireNonNull(model);
    }

    @Override
    public @NonNull EvalResult score(@NonNull EvalCase testCase, @NonNull String actualOutput) {
        EmbeddingClient.EmbedOptions options = EmbeddingClient.EmbedOptions.defaults(model)
            .withDimensions(embeddingClient.nativeDimensions());

        Result<float[], EmbeddingClient.EmbeddingError> expectedEmbed = embeddingClient.embed(testCase.expectedOutput(), options);
        Result<float[], EmbeddingClient.EmbeddingError> actualEmbed = embeddingClient.embed(actualOutput, options);

        if (expectedEmbed.isErr()) {
            return new EvalResult(testCase.id(), false, 0.0, actualOutput,
                "Embedding failed for expected output: " + expectedEmbed.unwrapErr().message());
        }
        if (actualEmbed.isErr()) {
            return new EvalResult(testCase.id(), false, 0.0, actualOutput,
                "Embedding failed for actual output: " + actualEmbed.unwrapErr().message());
        }

        float[] expectedVec = expectedEmbed.unwrap();
        float[] actualVec = actualEmbed.unwrap();

        double similarity = cosineSimilarity(expectedVec, actualVec);
        boolean passed = similarity >= threshold;

        String reasoning = String.format(
            "Cosine similarity: %.4f (threshold: %.4f)", similarity, threshold);

        return new EvalResult(testCase.id(), passed, similarity, actualOutput, reasoning);
    }

    private static double cosineSimilarity(float[] a, float[] b) {
        if (a.length != b.length) {
            throw new IllegalArgumentException("Vectors must have same dimensions");
        }
        double dot = 0.0;
        double normA = 0.0;
        double normB = 0.0;
        for (int i = 0; i < a.length; i++) {
            dot += a[i] * b[i];
            normA += a[i] * a[i];
            normB += b[i] * b[i];
        }
        if (normA == 0.0 || normB == 0.0) {
            return 0.0;
        }
        return dot / (Math.sqrt(normA) * Math.sqrt(normB));
    }
}
