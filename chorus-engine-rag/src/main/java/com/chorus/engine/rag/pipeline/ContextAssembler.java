package com.chorus.engine.rag.pipeline;

import com.chorus.engine.rag.document.Chunk;
import com.chorus.engine.rag.retrieval.RetrievalEngine;
import com.chorus.engine.rag.rerank.Reranker;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

import java.util.*;

/**
 * Assembles retrieved chunks into a context string for the LLM.
 *
 * <p>Handles:
 * <ul>
 *   <li>Token budget enforcement (truncation by priority)</li>
 *   <li>Citation tracking (maps chunks to citation IDs)</li>
 *   <li>Deduplication (same chunk from multiple retrieval paths)</li>
 *   <li>Ordering (by relevance score or original document order)</li>
 * </ul>
 */
public final class ContextAssembler {

    private final int maxContextTokens;
    private final int tokensPerChar;

    public ContextAssembler(int maxContextTokens) {
        this(maxContextTokens, 4);
    }

    public ContextAssembler(int maxContextTokens, int tokensPerChar) {
        this.maxContextTokens = maxContextTokens;
        this.tokensPerChar = tokensPerChar;
    }

    public @NonNull AssembledContext assemble(
        @NonNull String query,
        @NonNull List<RetrievalEngine.RetrievalResult> retrieved,
        @Nullable Reranker reranker,
        int rerankTopN
    ) {
        // Deduplicate by chunk ID, keeping highest score
        Map<String, RetrievalEngine.RetrievalResult> deduped = new LinkedHashMap<>();
        for (RetrievalEngine.RetrievalResult r : retrieved) {
            deduped.merge(r.chunk().id(), r, (a, b) -> a.score() >= b.score() ? a : b);
        }

        List<RetrievalEngine.RetrievalResult> candidates = new ArrayList<>(deduped.values());

        // Optional reranking
        if (reranker != null) {
            List<Chunk> chunks = candidates.stream().map(RetrievalEngine.RetrievalResult::chunk).toList();
            List<Reranker.RankedResult> ranked = reranker.rerank(query, chunks, rerankTopN);
            candidates = ranked.stream()
                .map(r -> new RetrievalEngine.RetrievalResult(r.chunk(), r.relevanceScore(), "reranked"))
                .toList();
        }

        // Sort by score descending
        candidates.sort(Comparator.comparingDouble(r -> -r.score()));

        // Build context within token budget
        StringBuilder context = new StringBuilder();
        List<Citation> citations = new ArrayList<>();
        int usedTokens = 0;
        int citationId = 1;

        for (RetrievalEngine.RetrievalResult r : candidates) {
            Chunk chunk = r.chunk();
            String citationTag = "[" + citationId + "]";
            String entry = citationTag + " " + chunk.text().trim() + "\n\n";
            int entryTokens = entry.length() / tokensPerChar;

            if (usedTokens + entryTokens > maxContextTokens) {
                break;
            }

            context.append(entry);
            usedTokens += entryTokens;
            citations.add(new Citation(citationId, chunk));
            citationId++;
        }

        return new AssembledContext(context.toString().trim(), citations, usedTokens);
    }

    public record AssembledContext(
        @NonNull String contextText,
        @NonNull List<Citation> citations,
        int usedTokens
    ) {}

    public record Citation(
        int id,
        @NonNull Chunk chunk
    ) {}
}
