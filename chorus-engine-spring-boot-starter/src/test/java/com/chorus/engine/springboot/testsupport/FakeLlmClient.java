package com.chorus.engine.springboot.testsupport;

import com.chorus.engine.core.reactive.CancellationToken;
import com.chorus.engine.llm.ChatRequest;
import com.chorus.engine.llm.ChatResponse;
import com.chorus.engine.llm.LlmClient;
import com.chorus.engine.core.context.Message;
import com.chorus.engine.core.context.TokenCount;
import com.chorus.engine.llm.StreamEvent;
import org.jspecify.annotations.NonNull;

import java.time.Duration;
import java.util.concurrent.Flow;
import java.util.concurrent.SubmissionPublisher;

/**
 * Hand-written fake LLM client for testing.
 *
 * <p>By default returns a canned assistant message.
 * Enqueued responses are returned in FIFO order for deterministic testing.
 */
public final class FakeLlmClient implements LlmClient {

    private final java.util.concurrent.ConcurrentLinkedQueue<ChatResponse> responses =
        new java.util.concurrent.ConcurrentLinkedQueue<>();

    private ChatResponse defaultResponse = new ChatResponse(
        "fake-id",
        "fake-model",
        "fake-provider",
        Message.assistant("Hello from fake LLM"),
        new TokenCount(1, 1, "fake"),
        Duration.ZERO,
        "stop",
        null,
        null,
        java.util.Map.of()
    );

    public void enqueue(@NonNull ChatResponse response) {
        responses.add(response);
    }

    public void setDefaultResponse(@NonNull ChatResponse response) {
        this.defaultResponse = response;
    }

    @Override
    public Flow.@NonNull Publisher<StreamEvent> stream(@NonNull ChatRequest request, @NonNull CancellationToken cancellationToken) {
        SubmissionPublisher<StreamEvent> publisher = new SubmissionPublisher<>();
        publisher.submit(new StreamEvent.Finish("stop", 1, 1));
        publisher.close();
        return publisher;
    }

    @Override
    public @NonNull ChatResponse complete(@NonNull ChatRequest request, @NonNull CancellationToken cancellationToken) {
        ChatResponse polled = responses.poll();
        return polled != null ? polled : defaultResponse;
    }

    @Override
    public @NonNull HealthStatus health() {
        return HealthStatus.HEALTHY;
    }

    @Override
    public @NonNull String providerName() {
        return "fake";
    }
}
