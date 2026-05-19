package com.chorus.engine.rag.query;

import com.chorus.engine.llm.*;
import com.chorus.engine.core.context.Message;
import com.chorus.engine.core.reactive.CancellationToken;
import org.jspecify.annotations.NonNull;

import java.util.ArrayList;
import java.util.List;

/**
 * Query decomposition: breaks a complex multi-part question into
 * simpler sub-questions that can be answered independently and combined.
 *
 * <p>Example: "Compare the revenue growth of Apple and Microsoft in 2024"
 * → "What was Apple's revenue growth in 2024?"
 * → "What was Microsoft's revenue growth in 2024?"
 *
 * <p>Cost: 1 LLM call per query. Results are retrieved for each sub-question
 * and merged.
 */
public final class DecompositionTransformer implements QueryTransformer {

    private final LlmClient llmClient;
    private final String model;
    private final int maxSubQueries;

    public DecompositionTransformer(@NonNull LlmClient llmClient, @NonNull String model, int maxSubQueries) {
        this.llmClient = llmClient;
        this.model = model;
        this.maxSubQueries = maxSubQueries;
    }

    @Override
    public @NonNull List<String> transform(@NonNull String query, @NonNull TransformContext context) {
        String prompt = """
            Break the following complex question into %d or fewer simpler sub-questions.
            Each sub-question should be answerable independently.
            List each sub-question on a new line. Do not add explanations.

            Question: %s
            """.formatted(maxSubQueries, query);

        ChatRequest request = ChatRequest.builder()
            .model(model)
            .messages(List.of(Message.user(prompt)))
            .temperature(0.2)
            .maxTokens(400)
            .build();

        ChatResponse response = llmClient.complete(request, CancellationToken.create());
        String text = response.message().content().trim();

        List<String> subQueries = new ArrayList<>();
        for (String line : text.split("\n")) {
            line = line.trim();
            if (line.isEmpty()) continue;
            line = line.replaceFirst("^\\d+\\.\\s*", "").replaceFirst("^[-*]\\s*", "");
            if (!line.isEmpty() && subQueries.size() < maxSubQueries) {
                subQueries.add(line);
            }
        }

        // Always include original if decomposition failed
        if (subQueries.isEmpty()) {
            subQueries.add(query);
        }
        return subQueries;
    }
}
