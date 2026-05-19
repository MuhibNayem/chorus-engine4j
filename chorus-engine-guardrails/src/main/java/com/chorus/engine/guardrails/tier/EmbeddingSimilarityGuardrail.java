package com.chorus.engine.guardrails.tier;

import com.chorus.engine.core.result.Result;
import com.chorus.engine.guardrails.Guardrail;
import com.chorus.engine.guardrails.GuardrailResult;
import com.chorus.engine.llm.embed.EmbeddingClient;
import org.jspecify.annotations.NonNull;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Tier 2 guardrail using embedding cosine similarity.
 *
 * <p>The embedding client is injected — zero coupling to any provider.
 * User provides the client and the set of forbidden example embeddings.
 */
public final class EmbeddingSimilarityGuardrail implements Guardrail {

    private final String name;
    private final List<float[]> forbiddenEmbeddings;
    private final double threshold;
    private final EmbeddingClient embeddingClient;
    private final String embedModel;
    private final ConcurrentHashMap<String, float[]> cache;

    public EmbeddingSimilarityGuardrail(
        @NonNull String name,
        @NonNull List<float[]> forbiddenEmbeddings,
        double threshold,
        @NonNull EmbeddingClient embeddingClient,
        @NonNull String embedModel,
        int cacheSize
    ) {
        this.name = name;
        this.forbiddenEmbeddings = List.copyOf(forbiddenEmbeddings);
        this.threshold = threshold;
        this.embeddingClient = embeddingClient;
        this.embedModel = embedModel;
        this.cache = new ConcurrentHashMap<>(cacheSize);
    }

    @Override public @NonNull String name() { return name; }
    @Override public int tier() { return 2; }

    @Override
    public @NonNull GuardrailResult evaluate(@NonNull String input, @NonNull GuardrailContext context) {
        Objects.requireNonNull(input, "input");
        Objects.requireNonNull(context, "context");
        Instant start = Instant.now();

        float[] inputEmbedding = cache.computeIfAbsent(input, k -> {
            Result<float[], EmbeddingClient.EmbeddingError> r = embeddingClient.embed(k,
                EmbeddingClient.EmbedOptions.defaults(embedModel));
            return r.isOk() ? r.unwrap() : new float[0];
        });

        if (inputEmbedding.length == 0) {
            return GuardrailResult.allow(name, 2, Duration.between(start, Instant.now()));
        }

        double maxSim = -1.0;
        for (float[] forbidden : forbiddenEmbeddings) {
            double sim = cosineSimilarity(inputEmbedding, forbidden);
            if (sim > maxSim) maxSim = sim;
        }

        Duration latency = Duration.between(start, Instant.now());
        if (maxSim + 1e-6 >= threshold) {
            return GuardrailResult.block(name, 2, "similarity=" + String.format("%.3f", maxSim), maxSim, latency);
        }
        return GuardrailResult.allow(name, 2, latency);
    }

    private double cosineSimilarity(float[] a, float[] b) {
        if (a.length != b.length) return -1.0;
        double dot = 0.0, na = 0.0, nb = 0.0;
        for (int i = 0; i < a.length; i++) { dot += a[i] * b[i]; na += a[i] * a[i]; nb += b[i] * b[i]; }
        return (na == 0 || nb == 0) ? 0.0 : dot / (Math.sqrt(na) * Math.sqrt(nb));
    }
}
