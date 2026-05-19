package com.chorus.engine.rag.streaming;

import com.chorus.engine.rag.document.Chunk;
import com.chorus.engine.rag.retrieval.RetrievalEngine;
import com.chorus.engine.rag.rerank.Reranker;
import org.jspecify.annotations.NonNull;

import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * High-quality reranking stage using a cross-encoder or LLM judge.
 *
 * <p>Takes candidate chunks from upstream stages and re-scores them.
 * Typical latency: 200-1000ms. Quality: highest precision.
 */
public final class RerankedStage implements RetrievalStage {

    private final Reranker reranker;
    private final int topN;

    public RerankedStage(@NonNull Reranker reranker, int topN) {
        this.reranker = reranker;
        this.topN = topN;
    }

    @Override
    public @NonNull String name() { return "reranked"; }

    @Override
    public int priority() { return 3; }

    @Override
    public long estimatedLatencyMs() { return 500; }

    @Override
    public @NonNull CompletableFuture<List<Chunk>> retrieve(@NonNull String query, RetrievalEngine.@NonNull RetrieveOptions options) {
        // RerankedStage requires upstream results. It is invoked by the orchestrator
        // with the union of chunks from earlier stages, not called directly.
        throw new UnsupportedOperationException(
            "RerankedStage must be invoked by the orchestrator with candidate chunks. " +
            "Use orchestrator.rerank(query, candidates) instead.");
    }

    public @NonNull CompletableFuture<List<Chunk>> rerank(@NonNull String query, @NonNull List<Chunk> candidates) {
        return CompletableFuture.supplyAsync(() ->
            reranker.rerank(query, candidates, topN).stream()
                .map(Reranker.RankedResult::chunk)
                .toList()
        );
    }
}
