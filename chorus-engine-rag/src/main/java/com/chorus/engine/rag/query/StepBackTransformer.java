package com.chorus.engine.rag.query;

import com.chorus.engine.llm.LlmClient;
import com.chorus.engine.llm.ChatRequest;
import com.chorus.engine.llm.ChatResponse;
import com.chorus.engine.core.context.Message;
import com.chorus.engine.core.reactive.CancellationToken;
import org.jspecify.annotations.NonNull;

import java.util.List;

/**
 * Step-back prompting: generates a broader, principle-level version of the query
 * before the specific query. The broader query retrieves foundational concepts
 * which provide context for understanding the specific retrieval results.
 *
 * <p>Example: "Why did my ViT model overfit?" →
 * step-back: "What are common causes of overfitting in deep learning?"
 * original: "Why did my ViT model overfit?"
 *
 * <p>Cost: 1 additional LLM call per query.
 */
public final class StepBackTransformer implements QueryTransformer {

    private final LlmClient llmClient;
    private final String model;

    public StepBackTransformer(@NonNull LlmClient llmClient, @NonNull String model) {
        this.llmClient = llmClient;
        this.model = model;
    }

    @Override
    public @NonNull List<String> transform(@NonNull String query, @NonNull TransformContext context) {
        String prompt = """
            Given a specific user question, write a more general, high-level question
            that captures the underlying principle or domain. The step-back question
            should be broader and retrieve foundational knowledge that helps answer
            the specific question.

            Specific question: %s
            Step-back question:""".formatted(query);

        ChatRequest request = ChatRequest.builder()
            .model(model)
            .messages(List.of(Message.user(prompt)))
            .temperature(0.2)
            .maxTokens(200)
            .build();

        ChatResponse response = llmClient.complete(request, CancellationToken.create());
        String stepBackQuery = response.message().content().trim();
        return List.of(stepBackQuery, query);
    }
}
