package com.chorus.engine.rag.store;

import com.chorus.engine.core.vector.VectorOperations;
import com.chorus.engine.rag.document.Chunk;
import org.jspecify.annotations.NonNull;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * In-memory vector store using brute-force cosine similarity.
 * Suitable for testing and small corpora (< 100K chunks).
 * For production, replace with pgvector, Pinecone, Qdrant, etc.
 */
public final class InMemoryVectorStore implements VectorStore {

    private final Map<String, Chunk> chunks = new ConcurrentHashMap<>();
    private final VectorOperations vectorOps;

    public InMemoryVectorStore() {
        this(VectorOperations.autoDetect());
    }

    public InMemoryVectorStore(@NonNull VectorOperations vectorOps) {
        this.vectorOps = vectorOps;
    }

    @Override
    public void upsert(@NonNull List<Chunk> newChunks) {
        for (Chunk c : newChunks) chunks.put(c.id(), c);
    }

    @Override
    public @NonNull List<RetrievalResult> search(@NonNull float[] queryEmbedding, int topK, @NonNull Map<String, Object> filters) {
        return chunks.values().stream()
            .filter(c -> matchesFilters(c, filters))
            .map(c -> {
                float[] emb = c.embedding();
                if (emb == null) return new RetrievalResult(c, 0.0);
                double sim = vectorOps.cosineSimilarity(queryEmbedding, emb);
                return new RetrievalResult(c, sim);
            })
            .sorted(Comparator.comparingDouble(r -> -r.score()))
            .limit(topK)
            .toList();
    }

    @Override
    public void delete(@NonNull Set<String> chunkIds) {
        chunkIds.forEach(chunks::remove);
    }

    @Override
    public void deleteByDocument(@NonNull String documentId) {
        chunks.values().removeIf(c -> c.documentId().equals(documentId));
    }

    @Override
    public long count() { return chunks.size(); }

    @Override
    public @NonNull String storeName() { return "in_memory"; }

    private boolean matchesFilters(@NonNull Chunk chunk, @NonNull Map<String, Object> filters) {
        for (Map.Entry<String, Object> f : filters.entrySet()) {
            Object val = chunk.metadata().get(f.getKey());
            if (!Objects.equals(val, f.getValue())) return false;
        }
        return true;
    }
}
