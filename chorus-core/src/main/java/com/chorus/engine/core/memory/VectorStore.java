package com.chorus.engine.core.memory;

import java.util.List;
import java.util.Map;

/**
 * Vector store interface for semantic search and RAG.
 */
public interface VectorStore {

    void add(String id, List<Double> embedding, String content, Map<String, Object> metadata);

    List<SearchResult> search(List<Double> queryEmbedding, int topK);

    void delete(String id);
}
