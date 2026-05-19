package com.chorus.engine.memory;

import com.chorus.engine.core.context.Message;
import com.chorus.engine.core.result.Result;
import com.chorus.engine.llm.embed.EmbeddingClient;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Long-term memory using BM25 + embedding hybrid retrieval.
 *
 * <p>The embedding client is injected — zero coupling to any provider.
 * User supplies the client (OpenAI, Gemini, Ollama, vLLM, ONNX, custom).
 *
 * <p>Hybrid scoring: 0.6 * BM25 + 0.4 * cosine_similarity(embedding).
 */
public final class LongTermMemory {

    private final List<MemoryDocument> documents = Collections.synchronizedList(new ArrayList<>());
    private final Map<String, Double> idfCache = new ConcurrentHashMap<>();
    private final double k1;
    private final double b;
    private final double bm25Weight;
    private final double semanticWeight;
    private final @Nullable EmbeddingClient embeddingClient;
    private final String embedModel;
    private volatile double avgDocLength = 0.0;

    public LongTermMemory(@Nullable EmbeddingClient embeddingClient, @Nullable String embedModel) {
        this(embeddingClient, embedModel, 1.5, 0.75, 0.6, 0.4);
    }

    public LongTermMemory(@Nullable EmbeddingClient embeddingClient, @Nullable String embedModel,
                          double k1, double b, double bm25Weight, double semanticWeight) {
        this.embeddingClient = embeddingClient;
        this.embedModel = embedModel != null ? embedModel : "";
        this.k1 = k1;
        this.b = b;
        this.bm25Weight = bm25Weight;
        this.semanticWeight = semanticWeight;
    }

    public void store(@NonNull Message message, @NonNull String key, @Nullable Map<String, Object> metadata) {
        Objects.requireNonNull(message, "message");
        Objects.requireNonNull(key, "key");
        float[] embedding = computeEmbedding(message.content());
        MemoryDocument doc = new MemoryDocument(key, message, embedding,
            metadata != null ? Map.copyOf(metadata) : Map.of(), Instant.now());
        documents.add(doc);
        recomputeStats();
    }

    public @NonNull List<RetrievalResult> retrieve(@NonNull String query, int topK) {
        Objects.requireNonNull(query, "query");
        if (documents.isEmpty()) return List.of();

        float[] queryEmbedding = computeEmbedding(query);
        Set<String> queryTerms = tokenize(query);

        return documents.stream()
            .map(doc -> {
                double bm25 = computeBm25(doc, queryTerms);
                double semantic = (queryEmbedding != null && doc.embedding != null)
                    ? cosineSimilarity(queryEmbedding, doc.embedding) : 0.0;
                double score = bm25Weight * bm25 + semanticWeight * semantic;
                return new RetrievalResult(doc.key, doc.message, score, bm25, semantic, doc.timestamp);
            })
            .filter(r -> r.score >= 0.0)
            .sorted(Comparator.comparingDouble(r -> -r.score))
            .limit(topK)
            .toList();
    }

    public boolean delete(@NonNull String key) {
        boolean removed = documents.removeIf(d -> d.key.equals(key));
        if (removed) recomputeStats();
        return removed;
    }

    public int size() { return documents.size(); }

    private float @Nullable [] computeEmbedding(@NonNull String text) {
        if (embeddingClient == null || embedModel.isEmpty()) return null;
        Result<float[], EmbeddingClient.EmbeddingError> r = embeddingClient.embed(text,
            EmbeddingClient.EmbedOptions.defaults(embedModel));
        return r.isOk() ? r.unwrap() : null;
    }

    private void recomputeStats() {
        List<MemoryDocument> docs = List.copyOf(documents);
        if (docs.isEmpty()) { avgDocLength = 0.0; idfCache.clear(); return; }

        avgDocLength = docs.stream().mapToInt(d -> d.terms.size()).average().orElse(0.0);
        Map<String, Long> docFreq = new HashMap<>();
        for (MemoryDocument doc : docs) {
            for (String term : doc.terms) docFreq.merge(term, 1L, Long::sum);
        }
        int N = docs.size();
        for (Map.Entry<String, Long> e : docFreq.entrySet()) {
            idfCache.put(e.getKey(), Math.log((N - e.getValue() + 0.5) / (e.getValue() + 0.5) + 1.0));
        }
    }

    private double computeBm25(@NonNull MemoryDocument doc, @NonNull Set<String> queryTerms) {
        if (doc.terms.isEmpty()) return 0.0;
        double score = 0.0;
        int docLen = doc.terms.size();
        for (String term : queryTerms) {
            long tf = doc.termFreq.getOrDefault(term, 0L);
            if (tf == 0) continue;
            double idf = idfCache.getOrDefault(term, 0.0);
            score += idf * (tf * (k1 + 1)) / (tf + k1 * (1 - b + b * (docLen / avgDocLength)));
        }
        return score;
    }

    private double cosineSimilarity(float[] a, float[] b) {
        if (a.length == 0 || b.length == 0 || a.length != b.length) return 0.0;
        double dot = 0.0, na = 0.0, nb = 0.0;
        for (int i = 0; i < a.length; i++) { dot += a[i] * b[i]; na += a[i] * a[i]; nb += b[i] * b[i]; }
        return (na == 0 || nb == 0) ? 0.0 : dot / (Math.sqrt(na) * Math.sqrt(nb));
    }

    private static @NonNull Set<String> tokenize(@NonNull String text) {
        return Arrays.stream(text.toLowerCase().split("\\W+")).filter(s -> !s.isEmpty()).collect(java.util.stream.Collectors.toSet());
    }

    private static final class MemoryDocument {
        final @NonNull String key;
        final @NonNull Message message;
        final float @Nullable [] embedding;
        final @NonNull Map<String, Object> metadata;
        final @NonNull Instant timestamp;
        final @NonNull Set<String> terms;
        final @NonNull Map<String, Long> termFreq;

        MemoryDocument(@NonNull String key, @NonNull Message message, float @Nullable [] embedding,
                       @NonNull Map<String, Object> metadata, @NonNull Instant timestamp) {
            this.key = key;
            this.message = message;
            this.embedding = embedding;
            this.metadata = Map.copyOf(metadata);
            this.timestamp = timestamp;
            this.terms = tokenize(message.content());
            this.termFreq = terms.stream().collect(java.util.stream.Collectors.groupingBy(s -> s, java.util.stream.Collectors.counting()));
        }
    }

    public record RetrievalResult(@NonNull String key, @NonNull Message message, double score,
                                  double bm25Score, double semanticScore, @NonNull Instant timestamp) {}
}
