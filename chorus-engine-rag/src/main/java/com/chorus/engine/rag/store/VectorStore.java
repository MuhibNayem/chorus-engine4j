package com.chorus.engine.rag.store;

import com.chorus.engine.rag.document.Chunk;
import org.jspecify.annotations.NonNull;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Pluggable vector store for dense retrieval.
 *
 * <p>Zero coupling to any specific database. User provides the implementation:
 * pgvector, Pinecone, Weaviate, Qdrant, Milvus, Elasticsearch, Chroma, Redis, etc.
 */
public interface VectorStore {

    void upsert(@NonNull List<Chunk> chunks);

    @NonNull List<RetrievalResult> search(@NonNull float[] queryEmbedding, int topK, @NonNull Map<String, Object> filters);

    void delete(@NonNull Set<String> chunkIds);

    void deleteByDocument(@NonNull String documentId);

    long count();

    @NonNull String storeName();

    record RetrievalResult(@NonNull Chunk chunk, double score) {}
}
