package com.chorus.engine.rag.rerank;

import com.chorus.engine.llm.*;
import com.chorus.engine.core.context.Message;
import com.chorus.engine.core.reactive.CancellationToken;
import com.chorus.engine.rag.document.Chunk;
import org.jspecify.annotations.NonNull;

import java.util.*;
import java.util.concurrent.*;

/**
 * LLM-as-judge reranker. Scores each candidate independently with a relevance prompt.
 *
 * <p>Cost: N LLM calls for N candidates. Use with small candidate sets (10-20).
 * For larger sets, use a dedicated cross-encoder API.
 */
public final class LlmJudgeReranker implements Reranker {

    private final LlmClient llmClient;
    private final String model;
    private final Executor executor;

    public LlmJudgeReranker(@NonNull LlmClient llmClient, @NonNull String model) {
        this(llmClient, model, Executors.newVirtualThreadPerTaskExecutor());
    }

    public LlmJudgeReranker(@NonNull LlmClient llmClient, @NonNull String model, @NonNull Executor executor) {
        this.llmClient = llmClient;
        this.model = model;
        this.executor = executor;
    }

    @Override
    public @NonNull List<RankedResult> rerank(@NonNull String query, @NonNull List<Chunk> candidates, int topN) {
        Objects.requireNonNull(query, "query");
        Objects.requireNonNull(candidates, "candidates");
        List<Future<RankedResult>> futures = new ArrayList<>();

        for (Chunk chunk : candidates) {
            futures.add(CompletableFuture.supplyAsync(() -> scoreChunk(query, chunk), executor));
        }

        List<RankedResult> results = new ArrayList<>();
        for (Future<RankedResult> f : futures) {
            try {
                results.add(f.get());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } catch (ExecutionException e) {
                // Skip failed evaluations
            }
        }

        return results.stream()
            .sorted(Comparator.comparingDouble(r -> -r.relevanceScore()))
            .limit(topN)
            .toList();
    }

    private @NonNull RankedResult scoreChunk(@NonNull String query, @NonNull Chunk chunk) {
        String prompt = """
            Rate how relevant the following document passage is to answering the question.
            Respond with ONLY a number from 0 to 10, where 10 means perfectly relevant.
            No explanation, just the number.

            Question: %s

            Passage: %s

            Relevance (0-10):""".formatted(query, chunk.text());

        ChatRequest request = ChatRequest.builder()
            .model(model)
            .messages(List.of(Message.user(prompt)))
            .temperature(0.0)
            .maxTokens(10)
            .build();

        ChatResponse response = llmClient.complete(request, CancellationToken.create());
        String text = response.message().content().trim();
        double score = parseScore(text);
        return new RankedResult(chunk, score, "llm_judge");
    }

    private double parseScore(@NonNull String text) {
        var matcher = java.util.regex.Pattern.compile("\\d+(\\.\\d+)?").matcher(text);
        if (matcher.find()) {
            double val = Double.parseDouble(matcher.group());
            return Math.min(1.0, val / 10.0);
        }
        return 0.0;
    }
}
