package com.chorus.engine.springboot;

import com.chorus.engine.rag.document.Chunk;
import com.chorus.engine.rag.retrieval.HybridRetrievalEngine;
import org.jspecify.annotations.NonNull;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * Simple in-memory keyword index for demonstration and testing.
 * Splits chunk text into lowercase tokens and builds an inverted index.
 */
final class InMemoryKeywordIndex implements HybridRetrievalEngine.KeywordIndex {

    private final Map<String, Set<String>> index = new ConcurrentHashMap<>();
    private final Map<String, Chunk> chunks = new ConcurrentHashMap<>();

    @Override
    public @NonNull List<RetrievalResult> search(@NonNull String query, int topK, @NonNull Map<String, Object> filters) {
        String[] queryTerms = query.toLowerCase(Locale.ROOT).split("\\W+");
        Map<String, Double> scores = new HashMap<>();

        for (String term : queryTerms) {
            if (term.isBlank()) continue;
            Set<String> chunkIds = index.getOrDefault(term, Set.of());
            for (String chunkId : chunkIds) {
                Chunk chunk = chunks.get(chunkId);
                if (chunk != null && matchesFilters(chunk, filters)) {
                    scores.merge(chunkId, 1.0, Double::sum);
                }
            }
        }

        return scores.entrySet().stream()
            .map(e -> new RetrievalResult(chunks.get(e.getKey()), e.getValue()))
            .sorted(Comparator.comparingDouble(r -> -r.score()))
            .limit(topK)
            .collect(Collectors.toList());
    }

    @Override
    public void index(@NonNull List<Chunk> newChunks) {
        for (Chunk chunk : newChunks) {
            chunks.put(chunk.id(), chunk);
            String[] terms = chunk.text().toLowerCase(Locale.ROOT).split("\\W+");
            for (String term : terms) {
                if (term.isBlank()) continue;
                index.computeIfAbsent(term, k -> ConcurrentHashMap.newKeySet()).add(chunk.id());
            }
        }
    }

    @Override
    public void remove(@NonNull Set<String> chunkIds) {
        for (String chunkId : chunkIds) {
            chunks.remove(chunkId);
            index.values().forEach(set -> set.remove(chunkId));
        }
    }

    private boolean matchesFilters(@NonNull Chunk chunk, @NonNull Map<String, Object> filters) {
        for (Map.Entry<String, Object> f : filters.entrySet()) {
            Object val = chunk.metadata().get(f.getKey());
            if (!Objects.equals(val, f.getValue())) return false;
        }
        return true;
    }
}
