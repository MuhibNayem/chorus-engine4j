package com.chorus.engine.rag.query;

import com.chorus.engine.llm.LlmClient;
import com.chorus.engine.llm.ChatRequest;
import com.chorus.engine.llm.ChatResponse;
import com.chorus.engine.core.context.Message;
import com.chorus.engine.core.reactive.CancellationToken;
import org.jspecify.annotations.NonNull;

import java.util.List;

/**
 * HyDE (Hypothetical Document Embeddings) query transformer.
 *
 * <p>Instead of embedding the user's question, generates a hypothetical answer
 * and embeds that. This addresses the query-answer asymmetry in embedding space:
 * answer embeddings cluster near other answers, while questions sit elsewhere.
 *
 * <p>Cost: 1 additional LLM call per query. Parallelizable with baseline search.
 * Best for: short or ambiguous questions where the user doesn't know the terminology.
 */
public final class HydeTransformer implements QueryTransformer {

    private final LlmClient llmClient;
    private final String model;

    public HydeTransformer(@NonNull LlmClient llmClient, @NonNull String model) {
        this.llmClient = llmClient;
        this.model = model;
    }

    @Override
    public @NonNull List<String> transform(@NonNull String query, @NonNull TransformContext context) {
        String prompt = """
            Write a short passage that directly answers the following question.
            Use factual, encyclopedic tone. Do not hedge or say "I don't know."
            Write as if you are quoting from a reliable source.

            Question: %s
            """.formatted(query);

        ChatRequest request = ChatRequest.builder()
            .model(model)
            .messages(List.of(Message.user(prompt)))
            .temperature(0.3)
            .maxTokens(300)
            .build();

        ChatResponse response = llmClient.complete(request, CancellationToken.create());

        return List.of(response.message().content().trim());
    }
}
