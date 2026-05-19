package com.chorus.engine.rag.streaming;

import com.chorus.engine.core.context.Message;
import com.chorus.engine.core.reactive.CancellationToken;
import com.chorus.engine.llm.ChatRequest;
import com.chorus.engine.llm.LlmClient;
import com.chorus.engine.llm.StreamEvent;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.Flow;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

/**
 * Manages a single LLM generation call.
 *
 * <p>Emits {@link RagStreamEvent.Token} events as they arrive from the LLM,
 * then emits {@link RagStreamEvent.GenerationCompleted} when the stream ends.
 * Tracks token count, latency, and finish reason for telemetry.
 *
 * <p>One-shot: a {@link GenerationController} instance is used for exactly
 * one generation. Create a new instance for each generation attempt.
 */
public final class GenerationController {

    private final String generationId;
    private final LlmClient llmClient;
    private final String model;
    private final double temperature;
    private final int maxTokens;
    private final AtomicInteger tokensEmitted = new AtomicInteger(0);
    private final AtomicReference<String> finishReason = new AtomicReference<>("");
    private final AtomicReference<Duration> latency = new AtomicReference<>(Duration.ZERO);

    public GenerationController(
        @NonNull String generationId,
        @NonNull LlmClient llmClient,
        @NonNull String model,
        double temperature,
        int maxTokens
    ) {
        this.generationId = generationId;
        this.llmClient = llmClient;
        this.model = model;
        this.temperature = temperature;
        this.maxTokens = maxTokens;
    }

    public @NonNull String generationId() { return generationId; }
    public int tokensEmitted() { return tokensEmitted.get(); }
    public @Nullable String finishReason() { return finishReason.get(); }
    public @Nullable Duration latency() { return latency.get(); }

    /**
     * Start generation and stream events to the provided consumer.
     *
     * @param query        the user query
     * @param contextText  the assembled context string
     * @param token        cancellation token (external cancellation)
     * @param eventSink    consumer for generation events
     * @return true if generation ran to completion, false if interrupted
     */
    public boolean start(
        @NonNull String query,
        @NonNull String contextText,
        @NonNull CancellationToken token,
        @NonNull Consumer<RagStreamEvent> eventSink
    ) {
        Instant start = Instant.now();
        String prompt = buildPrompt(query, contextText);
        ChatRequest request = ChatRequest.builder()
            .model(model)
            .messages(List.of(Message.user(prompt)))
            .temperature(temperature)
            .maxTokens(maxTokens)
            .build();

        java.util.concurrent.CountDownLatch latch = new java.util.concurrent.CountDownLatch(1);
        AtomicReference<Boolean> completedNormally = new AtomicReference<>(false);

        llmClient.stream(request, token).subscribe(new Flow.Subscriber<>() {
            @Override public void onSubscribe(Flow.Subscription s) { s.request(Long.MAX_VALUE); }

            @Override public void onNext(StreamEvent event) {
                if (token.isCancelled()) return;
                switch (event) {
                    case StreamEvent.Token t -> {
                        int idx = tokensEmitted.incrementAndGet();
                        eventSink.accept(new RagStreamEvent.Token(
                            Instant.now(), t.token(), idx, generationId));
                    }
                    case StreamEvent.Finish f -> {
                        finishReason.set(f.finishReason() != null ? f.finishReason() : "stop");
                    }
                    default -> {}
                }
            }

            @Override public void onError(Throwable t) {
                finishReason.set("error: " + t.getMessage());
                latency.set(Duration.between(start, Instant.now()));
                latch.countDown();
            }

            @Override public void onComplete() {
                completedNormally.set(true);
                latency.set(Duration.between(start, Instant.now()));
                latch.countDown();
            }
        });

        try {
            latch.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        boolean success = Boolean.TRUE.equals(completedNormally.get());
        if (success && !token.isCancelled()) {
            eventSink.accept(new RagStreamEvent.GenerationCompleted(
                Instant.now(), generationId,
                prompt.length() / 4, tokensEmitted.get(),
                latency.get() != null ? latency.get().toMillis() : 0,
                finishReason.get() != null ? finishReason.get() : "unknown"));
        }

        return success && !token.isCancelled();
    }

    private @NonNull String buildPrompt(@NonNull String query, @NonNull String contextText) {
        return """
            You are a helpful assistant. Use the provided context to answer the question.
            If the context does not contain the answer, say "I don't have enough information."
            Cite sources using [1], [2], etc. format.

            Context:
            %s

            Question: %s
            """.formatted(contextText, query);
    }
}
