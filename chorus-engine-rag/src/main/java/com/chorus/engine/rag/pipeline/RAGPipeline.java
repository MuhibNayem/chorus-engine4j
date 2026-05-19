package com.chorus.engine.rag.pipeline;

import com.chorus.engine.core.result.Result;
import com.chorus.engine.llm.*;
import com.chorus.engine.core.context.Message;
import com.chorus.engine.core.reactive.CancellationToken;
import com.chorus.engine.llm.embed.EmbeddingClient;
import com.chorus.engine.rag.chunking.ChunkingStrategy;
import com.chorus.engine.rag.document.Chunk;
import com.chorus.engine.rag.document.Document;
import com.chorus.engine.rag.query.QueryTransformer;
import com.chorus.engine.rag.retrieval.HybridRetrievalEngine;
import com.chorus.engine.rag.retrieval.RetrievalEngine;
import com.chorus.engine.rag.rerank.Reranker;
import com.chorus.engine.rag.store.VectorStore;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

import java.time.Instant;
import java.util.*;

/**
 * End-to-end RAG pipeline: chunk → embed → index → retrieve → rerank → generate.
 *
 * <p>Composable, testable, zero framework magic. Every stage is pluggable.
 */
public final class RAGPipeline {

    private final ChunkingStrategy chunkingStrategy;
    private final EmbeddingClient embeddingClient;
    private final VectorStore vectorStore;
    private final HybridRetrievalEngine.KeywordIndex keywordIndex;
    private final RetrievalEngine retrievalEngine;
    private final QueryTransformer queryTransformer;
    private final Reranker reranker;
    private final ContextAssembler contextAssembler;
    private final LlmClient llmClient;
    private final String generationModel;

    public RAGPipeline(
        @NonNull ChunkingStrategy chunkingStrategy,
        @NonNull EmbeddingClient embeddingClient,
        @NonNull VectorStore vectorStore,
        HybridRetrievalEngine.@NonNull KeywordIndex keywordIndex,
        @Nullable QueryTransformer queryTransformer,
        @Nullable Reranker reranker,
        @NonNull ContextAssembler contextAssembler,
        @NonNull LlmClient llmClient,
        @NonNull String generationModel
    ) {
        this.chunkingStrategy = chunkingStrategy;
        this.embeddingClient = embeddingClient;
        this.vectorStore = vectorStore;
        this.keywordIndex = keywordIndex;
        this.retrievalEngine = new HybridRetrievalEngine(
            vectorStore, embeddingClient, keywordIndex, 20, 20, 60.0
        );
        this.queryTransformer = queryTransformer;
        this.reranker = reranker;
        this.contextAssembler = contextAssembler;
        this.llmClient = llmClient;
        this.generationModel = generationModel;
    }

    // ---- Ingestion ----

    public @NonNull List<Chunk> ingest(@NonNull Document document) {
        List<Chunk> chunks = chunkingStrategy.chunk(document);

        // Embed in batches
        List<String> texts = chunks.stream().map(Chunk::text).toList();
        EmbeddingClient.EmbedOptions opts = new EmbeddingClient.EmbedOptions(
            embeddingClient.modelName(),
            EmbeddingClient.EmbedOptions.InputType.DOCUMENT,
            embeddingClient.nativeDimensions(),
            true,
            EmbeddingClient.EmbedOptions.Quantization.FP32,
            Map.of()
        );

        Result<List<float[]>, EmbeddingClient.EmbeddingError> embedResult = embeddingClient.embedBatch(texts, opts);
        if (embedResult.isErr()) {
            throw new RuntimeException("Embedding failed: " + embedResult.unwrapErr().message());
        }

        List<float[]> embeddings = embedResult.unwrap();
        List<Chunk> embeddedChunks = new ArrayList<>();
        for (int i = 0; i < chunks.size(); i++) {
            embeddedChunks.add(chunks.get(i).withEmbedding(embeddings.get(i)));
        }

        vectorStore.upsert(embeddedChunks);
        keywordIndex.index(embeddedChunks);
        return embeddedChunks;
    }

    // ---- Query ----

    public @NonNull RagResult query(@NonNull String userQuery, RetrievalEngine.@NonNull RetrieveOptions options) {
        Instant start = Instant.now();

        // Query transformation
        List<String> queries;
        if (queryTransformer != null) {
            queries = queryTransformer.transform(userQuery, new QueryTransformer.TransformContext(List.of(), "general"));
        } else {
            queries = List.of(userQuery);
        }

        // Retrieve for each query variant
        List<RetrievalEngine.RetrievalResult> allResults = new ArrayList<>();
        for (String q : queries) {
            allResults.addAll(retrievalEngine.retrieve(q, options));
        }

        // Assemble context
        ContextAssembler.AssembledContext assembled = contextAssembler.assemble(userQuery, allResults, reranker, 10);

        // Generate answer
        String answer = generateAnswer(userQuery, assembled.contextText(), assembled.citations());

        return new RagResult(
            userQuery,
            answer,
            assembled.citations(),
            queries,
            assembled.usedTokens(),
            java.time.Duration.between(start, Instant.now())
        );
    }

    private @NonNull String generateAnswer(@NonNull String query, @NonNull String context, @NonNull List<ContextAssembler.Citation> citations) {
        String prompt = """
            You are a helpful assistant. Use the provided context to answer the question.
            If the context does not contain the answer, say "I don't have enough information."
            Cite sources using [1], [2], etc. format.

            Context:
            %s

            Question: %s
            """.formatted(context, query);

        ChatRequest request = ChatRequest.builder()
            .model(generationModel)
            .messages(List.of(Message.user(prompt)))
            .temperature(0.3)
            .maxTokens(2048)
            .build();

        ChatResponse response = llmClient.complete(request, CancellationToken.create());
        return response.message().content();
    }

    public record RagResult(
        @NonNull String originalQuery,
        @NonNull String answer,
        @NonNull List<ContextAssembler.Citation> citations,
        @NonNull List<String> transformedQueries,
        int contextTokensUsed,
        java.time.@NonNull Duration latency
    ) {}
}
