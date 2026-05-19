package com.chorus.engine.rag.query;

import com.chorus.engine.llm.LlmClient;
import com.chorus.engine.llm.ChatRequest;
import com.chorus.engine.llm.ChatResponse;
import com.chorus.engine.core.context.Message;
import com.chorus.engine.core.reactive.CancellationToken;
import org.jspecify.annotations.NonNull;

import java.util.ArrayList;
import java.util.List;

/**
 * Multi-query expansion: generates 3-5 reformulations of the user query
 * and retrieves for each. Results are merged and deduplicated before reranking.
 *
 * <p>Best for: ambiguous queries, short queries, queries with synonyms.
 * Cost: N additional embedding calls per query.
 */
public final class MultiQueryTransformer implements QueryTransformer {

    private final LlmClient llmClient;
    private final String model;
    private final int numQueries;

    public MultiQueryTransformer(@NonNull LlmClient llmClient, @NonNull String model, int numQueries) {
        this.llmClient = llmClient;
        this.model = model;
        this.numQueries = numQueries;
    }

    @Override
    public @NonNull List<String> transform(@NonNull String query, @NonNull TransformContext context) {
        String prompt = buildPrompt(query, context);
        ChatRequest request = ChatRequest.builder()
            .model(model)
            .messages(List.of(Message.user(prompt)))
            .temperature(0.3)
            .maxTokens(500)
            .build();

        ChatResponse response = llmClient.complete(request, CancellationToken.create());

        // Parse reformulations from response
        List<String> queries = new ArrayList<>();
        queries.add(query); // Always include original

        String text = response.message().content();
        for (String line : text.split("\n")) {
            line = line.trim();
            if (line.isEmpty()) continue;
            // Remove numbering like "1.", "-", "*"
            line = line.replaceFirst("^\\d+\\.\\s*", "").replaceFirst("^[-*]\\s*", "");
            if (!line.equalsIgnoreCase(query) && !queries.contains(line) && queries.size() < numQueries + 1) {
                queries.add(line);
            }
        }
        return queries;
    }

    private @NonNull String buildPrompt(@NonNull String query, @NonNull TransformContext context) {
        return """
            You are a query expansion assistant. Generate %d alternative phrasings of the user's question
            that capture the same intent using different words. Each on a new line. Do not add explanations.

            Original question: %s
            """.formatted(numQueries, query);
    }
}
