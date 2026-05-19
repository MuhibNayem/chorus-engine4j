package com.chorus.engine.core.memory;

import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.filter.Filter;
import org.springframework.ai.vectorstore.filter.FilterExpressionBuilder;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Enterprise-grade vector store adapter wrapping Spring AI 2.0's {@link VectorStore}.
 *
 * <p>Supports:</p>
 * <ul>
 *   <li>Document storage with metadata</li>
 *   <li>Similarity search with configurable top-K and threshold</li>
 *   <li>Metadata filtering using Spring AI's Filter DSL</li>
 *   <li>Batch operations</li>
 *   <li>Score-returning search results</li>
 * </ul>
 *
 * <p>Compatible with all Spring AI vector store implementations:</p>
 * <ul>
 *   <li>pgvector (PostgreSQL) — pragmatic default for existing Postgres users</li>
 *   <li>Redis — in-memory with persistence</li>
 *   <li>Elasticsearch — for full-text + hybrid search</li>
 *   <li>Qdrant — purpose-built vector DB</li>
 *   <li>Chroma, Weaviate, Pinecone, Milvus, Neo4j, etc.</li>
 * </ul>
 */
public class ChorusVectorStore {

    private final VectorStore vectorStore;
    private final EmbeddingModel embeddingModel;
    private final double defaultSimilarityThreshold;
    private final int defaultTopK;

    public ChorusVectorStore(VectorStore vectorStore, EmbeddingModel embeddingModel) {
        this(vectorStore, embeddingModel, 0.7, 5);
    }

    public ChorusVectorStore(VectorStore vectorStore, EmbeddingModel embeddingModel,
                              double defaultSimilarityThreshold, int defaultTopK) {
        this.vectorStore = vectorStore;
        this.embeddingModel = embeddingModel;
        this.defaultSimilarityThreshold = defaultSimilarityThreshold;
        this.defaultTopK = defaultTopK;
    }

    // === Write operations ===

    /**
     * Add a document with content and metadata.
     */
    public void add(String id, String content, Map<String, Object> metadata) {
        Document doc = Document.builder()
            .id(id)
            .text(content)
            .metadata(metadata != null ? metadata : Map.of())
            .build();
        vectorStore.add(List.of(doc));
    }

    /**
     * Add multiple documents.
     */
    public void addAll(List<Document> documents) {
        vectorStore.add(documents);
    }

    /**
     * Delete documents by ID.
     */
    public void delete(String... ids) {
        vectorStore.delete(List.of(ids));
    }

    /**
     * Delete documents matching a filter expression.
     */
    public void deleteByFilter(Filter.Expression filter) {
        vectorStore.delete(filter);
    }

    // === Search operations ===

    /**
     * Search by query text using the configured embedding model.
     */
    public List<SearchResult> search(String query) {
        return search(query, defaultTopK, defaultSimilarityThreshold, null);
    }

    /**
     * Search with custom top-K.
     */
    public List<SearchResult> search(String query, int topK) {
        return search(query, topK, defaultSimilarityThreshold, null);
    }

    /**
     * Search with top-K and similarity threshold.
     */
    public List<SearchResult> search(String query, int topK, double similarityThreshold) {
        return search(query, topK, similarityThreshold, null);
    }

    /**
     * Search with metadata filter expression (e.g., {@code "country == 'UK' && year >= 2020"}).
     */
    public List<SearchResult> search(String query, String filterExpression) {
        return search(query, defaultTopK, defaultSimilarityThreshold, filterExpression);
    }

    /**
     * Full-featured search with all parameters.
     */
    public List<SearchResult> search(String query, int topK, double similarityThreshold,
                                      String filterExpression) {
        SearchRequest.Builder requestBuilder = SearchRequest.builder()
            .query(query)
            .topK(topK)
            .similarityThreshold(similarityThreshold);

        if (filterExpression != null && !filterExpression.isBlank()) {
            requestBuilder.filterExpression(filterExpression);
        }

        List<Document> results = vectorStore.similaritySearch(requestBuilder.build());
        return toSearchResults(results);
    }

    /**
     * Search with programmatic filter DSL.
     */
    public List<SearchResult> search(String query, Filter.Expression filter) {
        SearchRequest request = SearchRequest.builder()
            .query(query)
            .topK(defaultTopK)
            .similarityThreshold(defaultSimilarityThreshold)
            .filterExpression(filter)
            .build();

        List<Document> results = vectorStore.similaritySearch(request);
        return toSearchResults(results);
    }

    // === Utility ===

    private List<SearchResult> toSearchResults(List<Document> documents) {
        return documents.stream()
            .map(doc -> new SearchResult(
                doc.getId(),
                doc.getText(),
                doc.getScore() != null ? doc.getScore() : 0.0,
                doc.getMetadata()
            ))
            .collect(Collectors.toList());
    }

    /**
     * Get the underlying Spring AI VectorStore.
     */
    public VectorStore getSpringAiVectorStore() {
        return vectorStore;
    }

    /**
     * Get the embedding model.
     */
    public EmbeddingModel getEmbeddingModel() {
        return embeddingModel;
    }

    /**
     * Create a filter expression builder for complex queries.
     */
    public FilterExpressionBuilder filterBuilder() {
        return new FilterExpressionBuilder();
    }
}
