package com.chorus.engine.rag.self;

import com.chorus.engine.llm.*;
import com.chorus.engine.core.context.Message;
import com.chorus.engine.core.reactive.CancellationToken;
import com.chorus.engine.rag.document.Chunk;
import com.chorus.engine.rag.retrieval.RetrievalEngine;
import org.jspecify.annotations.NonNull;

import java.util.*;

/**
 * Self-RAG: the system evaluates its own retrieved chunks for relevance
 * and triggers iterative refinement if quality is insufficient.
 *
 * <p>Process:
 * <ol>
 *   <li>Retrieve initial chunks</li>
 *   <li>LLM grades each chunk: relevant / partially relevant / irrelevant</li>
 *   <li>If insufficient relevant chunks: reformulate query and re-retrieve</li>
 *   <li>Max 2 refinement rounds to control cost</li>
 * </ol>
 */
public final class SelfRagEvaluator {

    private final LlmClient llmClient;
    private final String model;
    private final RetrievalEngine retrievalEngine;
    private final int maxRefinements;
    private final double relevanceThreshold;

    public SelfRagEvaluator(
        @NonNull LlmClient llmClient,
        @NonNull String model,
        @NonNull RetrievalEngine retrievalEngine,
        int maxRefinements,
        double relevanceThreshold
    ) {
        this.llmClient = llmClient;
        this.model = model;
        this.retrievalEngine = retrievalEngine;
        this.maxRefinements = maxRefinements;
        this.relevanceThreshold = relevanceThreshold;
    }

    public @NonNull SelfRagResult evaluateAndRefine(@NonNull String query, @NonNull List<RetrievalEngine.RetrievalResult> initialResults) {
        List<RetrievalEngine.RetrievalResult> currentResults = new ArrayList<>(initialResults);
        List<RefinementRound> rounds = new ArrayList<>();
        int totalTokens = 0;

        for (int round = 0; round <= maxRefinements; round++) {
            List<GradedChunk> graded = gradeChunks(query, currentResults);
            long relevantCount = graded.stream().filter(g -> g.grade() == Grade.RELEVANT).count();
            double avgScore = graded.stream().mapToDouble(GradedChunk::confidence).average().orElse(0.0);

            rounds.add(new RefinementRound(round, graded, relevantCount, avgScore));

            if (relevantCount >= 3 || round == maxRefinements) {
                break;
            }

            // Reformulate query and re-retrieve
            String reformulated = reformulateQuery(query, graded);
            List<RetrievalEngine.RetrievalResult> newResults = retrievalEngine.retrieve(
                reformulated, RetrievalEngine.RetrieveOptions.defaults(15)
            );

            // Merge with existing, deduplicate
            Set<String> seen = new HashSet<>();
            for (RetrievalEngine.RetrievalResult r : currentResults) seen.add(r.chunk().id());
            for (RetrievalEngine.RetrievalResult r : newResults) {
                if (!seen.contains(r.chunk().id())) {
                    currentResults.add(r);
                    seen.add(r.chunk().id());
                }
            }
        }

        return new SelfRagResult(currentResults, rounds, totalTokens);
    }

    private @NonNull List<GradedChunk> gradeChunks(@NonNull String query, @NonNull List<RetrievalEngine.RetrievalResult> results) {
        List<GradedChunk> graded = new ArrayList<>();

        for (RetrievalEngine.RetrievalResult r : results) {
            String prompt = """
                Grade how relevant the following passage is to answering the question.
                Respond with EXACTLY one of: RELEVANT, PARTIAL, IRRELEVANT
                No explanation.

                Question: %s
                Passage: %s

                Grade:""".formatted(query, r.chunk().text());

            ChatRequest request = ChatRequest.builder()
                .model(model)
                .messages(List.of(Message.user(prompt)))
                .temperature(0.0)
                .maxTokens(20)
                .build();

            ChatResponse response = llmClient.complete(request, CancellationToken.create());
            String text = response.message().content().trim().toUpperCase();

            Grade grade;
            if (text.contains("RELEVANT") && !text.contains("IRRELEVANT")) {
                grade = Grade.RELEVANT;
            } else if (text.contains("PARTIAL")) {
                grade = Grade.PARTIAL;
            } else {
                grade = Grade.IRRELEVANT;
            }

            graded.add(new GradedChunk(r.chunk(), r.score(), grade, r.score()));
        }

        return graded;
    }

    private @NonNull String reformulateQuery(@NonNull String original, @NonNull List<GradedChunk> graded) {
        StringBuilder context = new StringBuilder("Previous search results:\n");
        for (GradedChunk g : graded) {
            context.append("- ").append(g.chunk().text(), 0, Math.min(100, g.chunk().text().length()))
                .append(" [").append(g.grade()).append("]\n");
        }

        String prompt = """
            %s

            The original question was: %s
            Some results were irrelevant. Write a better, more specific search query
            that might find more relevant documents. Respond with ONLY the query.
            """.formatted(context, original);

        ChatRequest request = ChatRequest.builder()
            .model(model)
            .messages(List.of(Message.user(prompt)))
            .temperature(0.2)
            .maxTokens(100)
            .build();

        ChatResponse response = llmClient.complete(request, CancellationToken.create());
        return response.message().content().trim();
    }

    public enum Grade { RELEVANT, PARTIAL, IRRELEVANT }

    public record GradedChunk(
        @NonNull Chunk chunk,
        double retrievalScore,
        @NonNull Grade grade,
        double confidence
    ) {}

    public record RefinementRound(
        int roundNumber,
        @NonNull List<GradedChunk> gradedChunks,
        long relevantCount,
        double averageConfidence
    ) {}

    public record SelfRagResult(
        @NonNull List<RetrievalEngine.RetrievalResult> finalResults,
        @NonNull List<RefinementRound> rounds,
        int totalTokensUsed
    ) {}
}
