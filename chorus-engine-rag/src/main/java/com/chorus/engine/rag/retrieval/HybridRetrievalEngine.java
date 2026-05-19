package com.chorus.engine.rag.retrieval;

import com.chorus.engine.core.result.Result;
import com.chorus.engine.llm.embed.EmbeddingClient;
import com.chorus.engine.rag.document.Chunk;
import com.chorus.engine.rag.store.VectorStore;
import org.jspecify.annotations.NonNull;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Hybrid retrieval: dense vector + sparse keyword + RRF fusion.
 *
 * <p>2026 production standard. Dense catches semantic similarity;
 * sparse catches exact terms, IDs, and jargon. RRF merges without
 * score normalization issues.
 */
public final class HybridRetrievalEngine implements RetrievalEngine {

    private final VectorStore vectorStore;
    private final EmbeddingClient embeddingClient;
    private final KeywordIndex keywordIndex;
    private final int denseK;
    private final int sparseK;
    private final double rrfK; // RRF constant, typically 60

    public HybridRetrievalEngine(
        @NonNull VectorStore vectorStore,
        @NonNull EmbeddingClient embeddingClient,
        @NonNull KeywordIndex keywordIndex,
        int denseK,
        int sparseK,
        double rrfK
    ) {
        this.vectorStore = vectorStore;
        this.embeddingClient = embeddingClient;
        this.keywordIndex = keywordIndex;
        this.denseK = denseK;
        this.sparseK = sparseK;
        this.rrfK = rrfK;
    }

    @Override
    public @NonNull List<RetrievalResult> retrieve(@NonNull String query, @NonNull RetrieveOptions options) {
        EmbeddingClient.EmbedOptions embedOpts = new EmbeddingClient.EmbedOptions(
            embeddingClient.modelName(),
            EmbeddingClient.EmbedOptions.InputType.QUERY,
            embeddingClient.nativeDimensions(),
            true,
            EmbeddingClient.EmbedOptions.Quantization.FP32,
            Map.of()
        );

        Result<float[], EmbeddingClient.EmbeddingError> embedResult = embeddingClient.embed(query, embedOpts);

        List<VectorStore.RetrievalResult> denseResults = embedResult.isOk()
            ? vectorStore.search(embedResult.unwrap(), denseK, options.filters())
            : List.of();

        List<KeywordIndex.RetrievalResult> sparseResults = keywordIndex.search(query, sparseK, options.filters());

        // RRF fusion
        Map<String, Double> rrfScores = new HashMap<>();
        Map<String, Chunk> chunkMap = new HashMap<>();

        for (int i = 0; i < denseResults.size(); i++) {
            String id = denseResults.get(i).chunk().id();
            rrfScores.merge(id, 1.0 / (rrfK + i + 1), Double::sum);
            chunkMap.put(id, denseResults.get(i).chunk());
        }

        for (int i = 0; i < sparseResults.size(); i++) {
            String id = sparseResults.get(i).chunk().id();
            rrfScores.merge(id, 1.0 / (rrfK + i + 1), Double::sum);
            chunkMap.put(id, sparseResults.get(i).chunk());
        }

        return rrfScores.entrySet().stream()
            .map(e -> new RetrievalResult(
                chunkMap.get(e.getKey()),
                e.getValue(),
                (denseResults.stream().anyMatch(d -> d.chunk().id().equals(e.getKey())) &&
                 sparseResults.stream().anyMatch(s -> s.chunk().id().equals(e.getKey())))
                    ? "hybrid"
                    : denseResults.stream().anyMatch(d -> d.chunk().id().equals(e.getKey())) ? "dense" : "sparse"
            ))
            .sorted(Comparator.comparingDouble(r -> -r.score()))
            .limit(options.topK())
            .toList();
    }

    /**
     * Pluggable keyword index (BM25, TF-IDF, inverted index).
     */
    public interface KeywordIndex {
        @NonNull List<RetrievalResult> search(@NonNull String query, int topK, @NonNull Map<String, Object> filters);
        void index(@NonNull List<Chunk> chunks);
        void remove(@NonNull Set<String> chunkIds);

        record RetrievalResult(@NonNull Chunk chunk, double score) {}
    }
}
