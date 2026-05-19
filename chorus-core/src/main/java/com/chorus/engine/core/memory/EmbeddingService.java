package com.chorus.engine.core.memory;

import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.EmbeddingModel;

import java.util.ArrayList;
import java.util.List;

/**
 * Enterprise-grade embedding service using Spring AI 2.0's {@link EmbeddingModel}.
 *
 * <p>2026 SOTA: Spring AI M6 provides native embedding APIs:</p>
 * <ul>
 *   <li>{@code embed(String)} → {@code float[]} for single text</li>
 *   <li>{@code embed(List<String>)} → {@code List<float[]>} for batch</li>
 *   <li>{@code embed(Document)} → {@code float[]} for document with metadata</li>
 *   <li>{@code dimensions()} → embedding dimension count</li>
 * </ul>
 */
public class EmbeddingService {

    private final EmbeddingModel embeddingModel;

    public EmbeddingService(EmbeddingModel embeddingModel) {
        this.embeddingModel = embeddingModel;
    }

    /**
     * Generate embedding for a single text.
     */
    public float[] embed(String text) {
        return embeddingModel.embed(text);
    }

    /**
     * Batch embed multiple texts.
     */
    public List<float[]> embed(List<String> texts) {
        return embeddingModel.embed(texts);
    }

    /**
     * Generate embedding for a Spring AI Document.
     */
    public float[] embed(Document document) {
        return embeddingModel.embed(document);
    }

    /**
     * Convert embedding to Chorus's List<Double> format.
     */
    public List<Double> embedAsList(String text) {
        float[] floats = embed(text);
        List<Double> result = new ArrayList<>(floats.length);
        for (float f : floats) result.add((double) f);
        return result;
    }

    /**
     * Get the embedding dimension.
     */
    public int dimensions() {
        return embeddingModel.dimensions();
    }

    /**
     * Create a Spring AI Document with embedding.
     */
    public Document createDocument(String id, String text, java.util.Map<String, Object> metadata) {
        Document doc = Document.builder()
            .id(id)
            .text(text)
            .metadata(metadata != null ? metadata : java.util.Map.of())
            .build();
        return doc;
    }
}
