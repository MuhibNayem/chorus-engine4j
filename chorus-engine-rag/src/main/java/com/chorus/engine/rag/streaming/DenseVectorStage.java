package com.chorus.engine.rag.streaming;

import com.chorus.engine.core.result.Result;
import com.chorus.engine.rag.document.Chunk;
import com.chorus.engine.rag.retrieval.RetrievalEngine;
import com.chorus.engine.rag.store.VectorStore;
import com.chorus.engine.llm.embed.EmbeddingClient;
import org.jspecify.annotations.NonNull;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * Dense vector retrieval using embeddings.
 *
 * <p>Typical latency: 50-300ms ( dominated by embedding API call).
 * Quality: high semantic recall, misses exact keyword matches.
 */
public final class DenseVectorStage implements RetrievalStage {

    private final VectorStore vectorStore;
    private final EmbeddingClient embeddingClient;
    private final int topK;

    public DenseVectorStage(@NonNull VectorStore vectorStore, @NonNull EmbeddingClient embeddingClient, int topK) {
        this.vectorStore = vectorStore;
        this.embeddingClient = embeddingClient;
        this.topK = topK;
    }

    @Override
    public @NonNull String name() { return "dense-vector"; }

    @Override
    public int priority() { return 2; }

    @Override
    public long estimatedLatencyMs() { return 150; }

    @Override
    public @NonNull CompletableFuture<List<Chunk>> retrieve(@NonNull String query, RetrievalEngine.@NonNull RetrieveOptions options) {
        return CompletableFuture.supplyAsync(() -> {
            EmbeddingClient.EmbedOptions embedOpts = new EmbeddingClient.EmbedOptions(
                embeddingClient.modelName(),
                EmbeddingClient.EmbedOptions.InputType.QUERY,
                embeddingClient.nativeDimensions(),
                true,
                EmbeddingClient.EmbedOptions.Quantization.FP32,
                Map.of()
            );

            Result<float[], EmbeddingClient.EmbeddingError> embedResult = embeddingClient.embed(query, embedOpts);
            if (embedResult.isErr()) {
                throw new RuntimeException("Embedding failed: " + embedResult.unwrapErr().message());
            }

            return vectorStore.search(embedResult.unwrap(), topK, options.filters()).stream()
                .map(VectorStore.RetrievalResult::chunk)
                .toList();
        });
    }
}
