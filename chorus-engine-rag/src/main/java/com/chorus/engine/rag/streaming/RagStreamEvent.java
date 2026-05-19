package com.chorus.engine.rag.streaming;

import com.chorus.engine.rag.document.Chunk;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

import java.time.Instant;
import java.util.List;

/**
 * Comprehensive event hierarchy for incremental RAG streaming.
 *
 * <p>Consumers receive a chronological stream of these events to render:
 * <ul>
 *   <li>Real-time token output (like ChatGPT)</li>
 *   <li>Retrieval progress indicators ("Searching documents...")</li>
 *   <li>Citation badges as sources are confirmed</li>
 *   <li>Supplemental context for post-answer enrichment</li>
 *   <li>Post-generation verification / correction events</li>
 * </ul>
 */
public sealed interface RagStreamEvent {

    Instant timestamp();

    /**
     * A token from the LLM generation stream.
     */
    record Token(
        @NonNull Instant timestamp,
        @NonNull String text,
        int tokenIndex,
        @Nullable String generationId
    ) implements RagStreamEvent {}

    /**
     * A retrieval stage has started.
     */
    record RetrievalStarted(
        @NonNull Instant timestamp,
        @NonNull String stageName,
        @NonNull String stageType, // "scout", "dense", "rerank"
        int expectedTopK
    ) implements RagStreamEvent {}

    /**
     * A retrieval stage has completed with results.
     */
    record RetrievalCompleted(
        @NonNull Instant timestamp,
        @NonNull String stageName,
        int resultsCount,
        long latencyMs,
        @NonNull List<Chunk> chunks
    ) implements RagStreamEvent {}

    /**
     * A retrieval stage failed. Non-fatal — pipeline continues with available context.
     */
    record RetrievalFailed(
        @NonNull Instant timestamp,
        @NonNull String stageName,
        @NonNull String errorType,
        @NonNull String errorMessage,
        boolean fatal
    ) implements RagStreamEvent {}

    /**
     * Context has been assembled from retrieved chunks and generation is starting.
     */
    record GenerationStarted(
        @NonNull Instant timestamp,
        @NonNull String generationId,
        int contextTokens,
        int contextChunks,
        @NonNull List<Citation> citations
    ) implements RagStreamEvent {}

    /**
     * Generation completed successfully.
     */
    record GenerationCompleted(
        @NonNull Instant timestamp,
        @NonNull String generationId,
        int promptTokens,
        int completionTokens,
        long generationLatencyMs,
        @NonNull String finishReason
    ) implements RagStreamEvent {}

    /**
     * Chunks arrived after generation started (PIPELINE / ADAPTIVE strategies).
     * Consumers can display these as "Additional sources" below the answer.
     */
    record SupplementalContext(
        @NonNull Instant timestamp,
        @NonNull String stageName,
        @NonNull List<Citation> citations,
        @NonNull String summary
    ) implements RagStreamEvent {}

    /**
     * Post-generation verification completed. The original answer was accurate.
     */
    record AnswerVerified(
        @NonNull Instant timestamp,
        @NonNull String generationId,
        double confidence,
        @Nullable String reasoning
    ) implements RagStreamEvent {}

    /**
     * Post-generation verification found that supplemental context contradicts
     * or adds to the original answer. The corrected text is provided.
     */
    record AnswerCorrected(
        @NonNull Instant timestamp,
        @NonNull String generationId,
        @NonNull String correction,
        @NonNull List<Citation> supportingCitations,
        @Nullable String reasoning
    ) implements RagStreamEvent {}

    /**
     * The entire incremental RAG session completed.
     */
    record SessionCompleted(
        @NonNull Instant timestamp,
        long totalLatencyMs,
        int totalRetrievalStages,
        int totalCitations,
        int totalTokensGenerated,
        @NonNull GenerationStrategy strategyUsed
    ) implements RagStreamEvent {}

    /**
     * Unrecoverable error — the session cannot continue.
     */
    record SessionFailed(
        @NonNull Instant timestamp,
        @NonNull String errorType,
        @NonNull String errorMessage
    ) implements RagStreamEvent {}

    record Citation(
        int index,
        @NonNull String chunkId,
        @NonNull String documentId,
        @NonNull String text,
        double relevanceScore,
        @NonNull String sourceStage
    ) {}
}
