package com.chorus.engine.rag.agentic;

import com.chorus.engine.llm.*;
import com.chorus.engine.core.context.Message;
import com.chorus.engine.core.reactive.CancellationToken;
import com.chorus.engine.rag.document.Chunk;
import com.chorus.engine.rag.retrieval.RetrievalEngine;
import com.chorus.engine.rag.store.VectorStore;
import org.jspecify.annotations.NonNull;

import java.util.*;

/**
 * Agentic RAG: a ReAct-style loop where the LLM decides retrieval strategy.
 *
 * <p>The agent can:
 * <ul>
 *   <li>Query the vector store with different formulations</li>
 *   <li>Decide when enough information has been gathered</li>
 *   <li>Reflect on retrieved results and search deeper if needed</li>
 *   <li>Handle multi-hop questions by chaining retrievals</li>
 * </ul>
 *
 * <p>Max iterations prevents runaway loops. Each iteration's cost is tracked.
 */
public final class AgenticRagOrchestrator {

    private final LlmClient llmClient;
    private final String model;
    private final VectorStore vectorStore;
    private final RetrievalEngine retrievalEngine;
    private final int maxIterations;

    public AgenticRagOrchestrator(
        @NonNull LlmClient llmClient,
        @NonNull String model,
        @NonNull VectorStore vectorStore,
        @NonNull RetrievalEngine retrievalEngine,
        int maxIterations
    ) {
        this.llmClient = llmClient;
        this.model = model;
        this.vectorStore = vectorStore;
        this.retrievalEngine = retrievalEngine;
        this.maxIterations = maxIterations;
    }

    public @NonNull AgenticResult execute(@NonNull String query) {
        Objects.requireNonNull(query, "query");
        List<Message> history = new ArrayList<>();
        history.add(Message.system("""
            You are an intelligent research assistant. You have access to a document retrieval system.
            For each user question, you may:
            1. Search the document store with a query
            2. Reflect on the results
            3. Search again with a refined query if needed
            4. Answer when you have sufficient information

            To search, respond with: SEARCH: <your search query>
            To answer, respond with: ANSWER: <your final answer>
            """));
        history.add(Message.user(query));

        List<RetrievalEngine.RetrievalResult> allRetrieved = new ArrayList<>();
        int iterations = 0;
        int totalTokens = 0;

        while (iterations < maxIterations) {
            ChatRequest request = ChatRequest.builder()
                .model(model)
                .messages(history)
                .temperature(0.2)
                .maxTokens(1024)
                .build();

            ChatResponse response = llmClient.complete(request, CancellationToken.create());
            String content = response.message().content().trim();
            totalTokens += response.tokenCount().total();

            if (content.startsWith("SEARCH:")) {
                String searchQuery = content.substring(7).trim();
                List<RetrievalEngine.RetrievalResult> results = retrievalEngine.retrieve(
                    searchQuery, RetrievalEngine.RetrieveOptions.defaults(10)
                );
                allRetrieved.addAll(results);

                String context = results.stream()
                    .map(r -> "- " + r.chunk().text())
                    .limit(5)
                    .reduce((a, b) -> a + "\n" + b)
                    .orElse("No relevant documents found.");

                history.add(Message.assistant(content));
                history.add(Message.user("Retrieved documents:\n" + context));
            } else if (content.startsWith("ANSWER:")) {
                String answer = content.substring(7).trim();
                return new AgenticResult(answer, allRetrieved, iterations + 1, totalTokens, true);
            } else {
                // Agent didn't follow format — treat as answer
                return new AgenticResult(content, allRetrieved, iterations + 1, totalTokens, false);
            }

            iterations++;
        }

        // Max iterations reached — force answer with what we have
        String finalPrompt = "Based on the retrieved documents, provide the best answer you can:\n\n" +
            allRetrieved.stream().map(r -> "- " + r.chunk().text()).limit(10)
                .reduce((a, b) -> a + "\n" + b).orElse("No documents retrieved.");

        ChatRequest finalRequest = ChatRequest.builder()
            .model(model)
            .messages(List.of(Message.user(finalPrompt)))
            .temperature(0.3)
            .maxTokens(2048)
            .build();

        ChatResponse finalResponse = llmClient.complete(finalRequest, CancellationToken.create());
        totalTokens += finalResponse.tokenCount().total();

        return new AgenticResult(
            finalResponse.message().content(),
            allRetrieved,
            iterations,
            totalTokens,
            false
        );
    }

    public record AgenticResult(
        @NonNull String answer,
        @NonNull List<RetrievalEngine.RetrievalResult> retrieved,
        int iterationsUsed,
        int totalTokens,
        boolean converged
    ) {}
}
