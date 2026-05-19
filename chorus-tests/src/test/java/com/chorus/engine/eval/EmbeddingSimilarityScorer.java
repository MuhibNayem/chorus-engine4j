package com.chorus.engine.eval;

import org.springframework.ai.embedding.EmbeddingModel;

/**
 * Scores outputs by computing cosine similarity of their embeddings.
 */
public class EmbeddingSimilarityScorer implements Scorer {

    private final EmbeddingModel embeddingModel;

    public EmbeddingSimilarityScorer(EmbeddingModel embeddingModel) {
        this.embeddingModel = embeddingModel;
    }

    @Override
    public double score(String expected, String actual) {
        if (expected == null || actual == null || embeddingModel == null) {
            return 0.0;
        }
        float[] expectedEmbedding = embeddingModel.embed(expected);
        float[] actualEmbedding = embeddingModel.embed(actual);
        return cosineSimilarity(expectedEmbedding, actualEmbedding);
    }

    private double cosineSimilarity(float[] a, float[] b) {
        if (a.length != b.length) {
            return 0.0;
        }
        double dot = 0.0;
        double normA = 0.0;
        double normB = 0.0;
        for (int i = 0; i < a.length; i++) {
            dot += a[i] * b[i];
            normA += a[i] * a[i];
            normB += b[i] * b[i];
        }
        double denominator = Math.sqrt(normA) * Math.sqrt(normB);
        return denominator == 0.0 ? 0.0 : dot / denominator;
    }
}
