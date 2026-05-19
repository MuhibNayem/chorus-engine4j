package com.chorus.engine.rag.rerank;

import com.chorus.engine.rag.document.Chunk;
import org.jspecify.annotations.NonNull;

import java.util.List;

/**
 * Cross-encoder or LLM-based reranker.
 *
 * <p>First-stage retrieval (dense/sparse) is fast but imprecise.
 * Reranking re-evaluates the top-K candidates with a more expensive
 * but accurate cross-attention model or LLM judge.
 *
 * <p>2026 strategies:
 * <ul>
 *   <li>Cross-encoder API (Cohere, Jina, BGE)</li>
 *   <li>LLM-as-judge (zero-shot relevance scoring)</li>
 *   <li>Pointwise / Pairwise / Listwise approaches</li>
 * </ul>
 */
public interface Reranker {

    @NonNull List<RankedResult> rerank(@NonNull String query, @NonNull List<Chunk> candidates, int topN);

    record RankedResult(
        @NonNull Chunk chunk,
        double relevanceScore,   // 0.0 - 1.0
        @NonNull String method   // "cross_encoder", "llm_judge"
    ) {}
}
