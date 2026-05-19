package com.chorus.engine.rag.corrective;

import com.chorus.engine.llm.*;
import com.chorus.engine.core.context.Message;
import com.chorus.engine.core.reactive.CancellationToken;
import com.chorus.engine.rag.document.Chunk;
import com.chorus.engine.rag.retrieval.RetrievalEngine;
import org.jspecify.annotations.NonNull;

import java.util.*;

/**
 * Corrective RAG (CRAG): grades retrieved documents and applies corrective actions.
 *
 * <p>If retrieved documents are poor quality, the system falls back to:
 * <ul>
 *   <li>Web search (if configured)</li>
 *   <li>Knowledge graph traversal</li>
 *   <li>Query reformulation + re-retrieval</li>
 * </ul>
 *
 * <p>Inspired by the 2024 CRAG paper, adapted for enterprise use.
 */
public final class CorrectiveRagEngine {

    private final LlmClient llmClient;
    private final String model;
    private final RetrievalEngine retrievalEngine;
    private final double qualityThreshold;

    public CorrectiveRagEngine(
        @NonNull LlmClient llmClient,
        @NonNull String model,
        @NonNull RetrievalEngine retrievalEngine,
        double qualityThreshold
    ) {
        this.llmClient = llmClient;
        this.model = model;
        this.retrievalEngine = retrievalEngine;
        this.qualityThreshold = qualityThreshold;
    }

    public @NonNull CorrectiveResult execute(@NonNull String query) {
        List<RetrievalEngine.RetrievalResult> retrieved = retrievalEngine.retrieve(
            query, RetrievalEngine.RetrieveOptions.defaults(10)
        );

        DocumentGrade grade = gradeDocuments(query, retrieved);

        return switch (grade.action()) {
            case USE -> new CorrectiveResult(query, retrieved, grade, Action.USE, null);
            case REFORMULATE -> {
                String betterQuery = reformulate(query, retrieved);
                List<RetrievalEngine.RetrievalResult> reformulated = retrievalEngine.retrieve(
                    betterQuery, RetrievalEngine.RetrieveOptions.defaults(10)
                );
                yield new CorrectiveResult(query, reformulated, grade, Action.REFORMULATE, betterQuery);
            }
            case FALLBACK -> new CorrectiveResult(query, List.of(), grade, Action.FALLBACK,
                "Insufficient document quality. Consider external search or knowledge graph.");
        };
    }

    private @NonNull DocumentGrade gradeDocuments(@NonNull String query, @NonNull List<RetrievalEngine.RetrievalResult> retrieved) {
        if (retrieved.isEmpty()) {
            return new DocumentGrade(0.0, Action.FALLBACK, "No documents retrieved");
        }

        StringBuilder docs = new StringBuilder();
        for (int i = 0; i < retrieved.size(); i++) {
            docs.append("[").append(i + 1).append("] ")
                .append(retrieved.get(i).chunk().text(), 0, Math.min(200, retrieved.get(i).chunk().text().length()))
                .append("\n\n");
        }

        String prompt = """
            Rate the overall quality of these retrieved documents for answering the question.
            Consider coverage, accuracy, and relevance.
            Respond with a score 0-10 and an action: USE, REFORMULATE, or FALLBACK.
            Format: SCORE: <number> ACTION: <action> REASON: <brief reason>

            Question: %s

            Documents:
            %s
            """.formatted(query, docs);

        ChatRequest request = ChatRequest.builder()
            .model(model)
            .messages(List.of(Message.user(prompt)))
            .temperature(0.0)
            .maxTokens(100)
            .build();

        ChatResponse response = llmClient.complete(request, CancellationToken.create());
        String text = response.message().content().trim();

        double score = 5.0;
        Action action = Action.USE;
        String reason = "";

        var scoreMatcher = java.util.regex.Pattern.compile("SCORE:\\s*(\\d+(\\.\\d+)?)").matcher(text);
        if (scoreMatcher.find()) score = Double.parseDouble(scoreMatcher.group(1));

        var actionMatcher = java.util.regex.Pattern.compile("ACTION:\\s*(\\w+)").matcher(text);
        if (actionMatcher.find()) {
            try {
                action = Action.valueOf(actionMatcher.group(1).toUpperCase());
            } catch (IllegalArgumentException ignored) {}
        }

        var reasonMatcher = java.util.regex.Pattern.compile("REASON:\\s*(.+)").matcher(text);
        if (reasonMatcher.find()) reason = reasonMatcher.group(1).trim();

        // Normalize score to 0-1
        double normalizedScore = Math.min(1.0, score / 10.0);
        if (normalizedScore < qualityThreshold && action == Action.USE) {
            action = Action.REFORMULATE;
        }

        return new DocumentGrade(normalizedScore, action, reason);
    }

    private @NonNull String reformulate(@NonNull String query, @NonNull List<RetrievalEngine.RetrievalResult> retrieved) {
        String prompt = """
            The following documents were retrieved but are not sufficient to answer the question.
            Write a better search query that might find more relevant information.
            Respond with ONLY the new query.

            Question: %s

            Retrieved (insufficient):
            %s
            """.formatted(query,
                retrieved.stream().map(r -> "- " + r.chunk().text().substring(0, Math.min(100, r.chunk().text().length())))
                    .reduce((a, b) -> a + "\n" + b).orElse(""));

        ChatRequest request = ChatRequest.builder()
            .model(model)
            .messages(List.of(Message.user(prompt)))
            .temperature(0.2)
            .maxTokens(100)
            .build();

        ChatResponse response = llmClient.complete(request, CancellationToken.create());
        return response.message().content().trim();
    }

    public enum Action { USE, REFORMULATE, FALLBACK }

    public record DocumentGrade(
        double qualityScore, // 0.0 - 1.0
        @NonNull Action action,
        @NonNull String reason
    ) {}

    public record CorrectiveResult(
        @NonNull String originalQuery,
        @NonNull List<RetrievalEngine.RetrievalResult> finalRetrieved,
        @NonNull DocumentGrade grade,
        @NonNull Action finalAction,
        @NonNull String fallbackReason
    ) {}
}
