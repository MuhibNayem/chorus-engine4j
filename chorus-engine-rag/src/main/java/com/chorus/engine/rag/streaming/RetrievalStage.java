package com.chorus.engine.rag.streaming;

import com.chorus.engine.rag.document.Chunk;
import com.chorus.engine.rag.retrieval.RetrievalEngine;
import org.jspecify.annotations.NonNull;

import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * A named, prioritized stage of the retrieval pipeline.
 *
 * <p>Stages execute concurrently. Lower {@link #priority()} values complete
 * earlier and feed the first generation attempt. Higher-priority stages
 * (slower but higher quality) may trigger a generation restart if they
 * finish before the restart deadline.
 *
 * <p>Production deployments typically configure:
 * <ul>
 *   <li>Priority 1: Scout — keyword/BM25, ~10ms, low quality</li>
 *   <li>Priority 2: Dense — vector search, ~50-200ms, good quality</li>
 *   <li>Priority 3: Reranked — cross-encoder, ~300-800ms, highest quality</li>
 * </ul>
 */
public interface RetrievalStage {

    @NonNull String name();

    /**
     * Lower number = higher priority (runs first).
     */
    int priority();

    /**
     * Estimated latency in milliseconds for capacity planning.
     */
    long estimatedLatencyMs();

    /**
     * Execute retrieval asynchronously.
     *
     * @param query   the user query
     * @param options retrieval options
     * @return future of retrieved chunks
     */
    @NonNull CompletableFuture<List<Chunk>> retrieve(@NonNull String query, RetrievalEngine.@NonNull RetrieveOptions options);
}
