package com.chorus.engine.rag.streaming;

import com.chorus.engine.rag.document.Chunk;
import com.chorus.engine.rag.retrieval.RetrievalEngine;
import com.chorus.engine.rag.retrieval.HybridRetrievalEngine;
import org.jspecify.annotations.NonNull;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;

/**
 * Fast scout retrieval using only the keyword index (no embedding latency).
 *
 * <p>Typical latency: 5-20ms. Quality: moderate — catches exact matches
 * and jargon but misses semantic paraphrases.
 */
public final class KeywordScoutStage implements RetrievalStage {

    private final HybridRetrievalEngine.KeywordIndex keywordIndex;
    private final int topK;

    public KeywordScoutStage(HybridRetrievalEngine.@NonNull KeywordIndex keywordIndex, int topK) {
        this.keywordIndex = keywordIndex;
        this.topK = topK;
    }

    @Override
    public @NonNull String name() { return "scout-keyword"; }

    @Override
    public int priority() { return 1; }

    @Override
    public long estimatedLatencyMs() { return 15; }

    @Override
    public @NonNull CompletableFuture<List<Chunk>> retrieve(@NonNull String query, RetrievalEngine.@NonNull RetrieveOptions options) {
        Objects.requireNonNull(query, "query");
        Objects.requireNonNull(options, "options");
        return CompletableFuture.supplyAsync(() ->
            keywordIndex.search(query, topK, options.filters()).stream()
                .map(HybridRetrievalEngine.KeywordIndex.RetrievalResult::chunk)
                .toList()
        );
    }
}
