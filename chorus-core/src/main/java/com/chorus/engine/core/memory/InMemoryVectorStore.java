package com.chorus.engine.core.memory;

import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * In-memory vector store with cosine similarity search.
 */
public class InMemoryVectorStore implements VectorStore {

    private final List<Entry> entries = new CopyOnWriteArrayList<>();

    @Override
    public void add(String id, List<Double> embedding, String content, Map<String, Object> metadata) {
        entries.add(new Entry(id, embedding, content, Map.copyOf(metadata)));
    }

    @Override
    public List<SearchResult> search(List<Double> queryEmbedding, int topK) {
        return entries.stream()
            .map(e -> new SearchResult(e.id, e.content, cosineSimilarity(queryEmbedding, e.embedding), e.metadata))
            .sorted(Comparator.comparingDouble(SearchResult::score).reversed())
            .limit(topK)
            .toList();
    }

    @Override
    public void delete(String id) {
        entries.removeIf(e -> e.id.equals(id));
    }

    private double cosineSimilarity(List<Double> a, List<Double> b) {
        double dot = 0.0, normA = 0.0, normB = 0.0;
        int minLen = Math.min(a.size(), b.size());
        for (int i = 0; i < minLen; i++) {
            dot += a.get(i) * b.get(i);
            normA += a.get(i) * a.get(i);
            normB += b.get(i) * b.get(i);
        }
        if (normA == 0.0 || normB == 0.0) return 0.0;
        return dot / (Math.sqrt(normA) * Math.sqrt(normB));
    }

    private record Entry(String id, List<Double> embedding, String content, Map<String, Object> metadata) {}
}
