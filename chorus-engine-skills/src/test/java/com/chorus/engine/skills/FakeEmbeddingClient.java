package com.chorus.engine.skills;

import com.chorus.engine.core.result.Result;
import com.chorus.engine.llm.embed.EmbeddingClient;


import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Fake embedding client for deterministic testing.
 */
final class FakeEmbeddingClient implements EmbeddingClient {

    private final Map<String, float[]> embeddings = new HashMap<>();
    private int nativeDimensions = 4;

    public void registerEmbedding(String text, float [] embedding) {
        embeddings.put(text, embedding);
    }

    public void setNativeDimensions(int nativeDimensions) {
        this.nativeDimensions = nativeDimensions;
    }

    @Override
    public Result<float[], EmbeddingError> embed(String text, EmbedOptions options) {
        float[] emb = embeddings.get(text);
        if (emb == null) {
            int dims = options.dimensions() > 0 ? options.dimensions() : nativeDimensions;
            emb = generateDeterministic(text, dims);
            embeddings.put(text, emb);
        }
        return Result.ok(emb);
    }

    @Override
    public Result<List<float[]>, EmbeddingError> embedBatch(List<String> texts, EmbedOptions options) {
        List<float[]> results = new java.util.ArrayList<>();
        for (String text : texts) {
            results.add(embed(text, options).unwrap());
        }
        return Result.ok(results);
    }

    @Override
    public String providerName() {
        return "fake";
    }

    @Override
    public String modelName() {
        return "fake-model";
    }

    @Override
    public int nativeDimensions() {
        return nativeDimensions;
    }

    @Override
    public boolean isLocal() {
        return true;
    }

    @Override
    public HealthStatus health() {
        return HealthStatus.HEALTHY;
    }

    private float [] generateDeterministic(String text, int dims) {
        float[] vec = new float[dims];
        int hash = text.hashCode();
        for (int i = 0; i < dims; i++) {
            vec[i] = (float) Math.sin(hash + i * 1.3);
        }
        double norm = 0.0;
        for (float v : vec) {
            norm += v * v;
        }
        if (norm > 0) {
            norm = Math.sqrt(norm);
            for (int i = 0; i < dims; i++) {
                vec[i] = (float) (vec[i] / norm);
            }
        }
        return vec;
    }
}
