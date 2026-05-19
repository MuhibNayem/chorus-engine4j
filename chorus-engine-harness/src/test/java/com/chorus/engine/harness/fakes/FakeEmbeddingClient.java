package com.chorus.engine.harness.fakes;

import com.chorus.engine.core.result.Result;
import com.chorus.engine.llm.embed.EmbeddingClient;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Hand-written fake embedding client for harness tests.
 */
public class FakeEmbeddingClient implements EmbeddingClient {

    private final Map<String, float[]> embeddings = new HashMap<>();
    private final int dimensions;
    private volatile boolean failNext = false;

    public FakeEmbeddingClient(int dimensions) {
        this.dimensions = dimensions;
    }

    public void setEmbedding(String text, float[] vector) {
        embeddings.put(text, vector.clone());
    }

    public void setFailNext(boolean fail) {
        this.failNext = fail;
    }

    @Override
    public Result<float[], EmbeddingError> embed(String text, EmbedOptions options) {
        if (failNext) {
            return Result.err(EmbeddingError.of("FAKE_ERROR", "Fake embedding failure", "fake-provider"));
        }
        float[] vec = embeddings.get(text);
        if (vec == null) {
            vec = deterministicVector(text);
        }
        return Result.ok(vec.clone());
    }

    @Override
    public Result<List<float[]>, EmbeddingError> embedBatch(List<String> texts, EmbedOptions options) {
        List<float[]> results = new ArrayList<>();
        for (String text : texts) {
            var r = embed(text, options);
            if (r.isErr()) return Result.err(r.unwrapErr());
            results.add(r.unwrap());
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
        return dimensions;
    }

    @Override
    public boolean isLocal() {
        return true;
    }

    @Override
    public HealthStatus health() {
        return HealthStatus.HEALTHY;
    }

    private float[] deterministicVector(String text) {
        float[] vec = new float[dimensions];
        int hash = text.hashCode();
        for (int i = 0; i < dimensions; i++) {
            vec[i] = (float) Math.sin(hash + i * 1.3);
        }
        return vec;
    }
}
