package com.chorus.engine.skills;

import com.chorus.engine.core.context.Message;
import com.chorus.engine.core.context.TokenCount;
import com.chorus.engine.core.reactive.CancellationToken;
import com.chorus.engine.llm.ChatRequest;
import com.chorus.engine.llm.ChatResponse;
import com.chorus.engine.llm.LlmClient;
import com.chorus.engine.llm.StreamEvent;

import java.time.Duration;
import java.util.Map;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.Flow;

/**
 * Fake LLM client for deterministic testing.
 */
final class FakeLlmClient implements LlmClient {

    private final Queue<ChatResponse> responses = new ConcurrentLinkedQueue<>();

    public void enqueue(ChatResponse response) {
        responses.add(response);
    }

    @Override
    public Flow.Publisher<StreamEvent> stream(ChatRequest request, CancellationToken cancellationToken) {
        ChatResponse response = responses.poll();
        if (response == null) {
            return subscriber -> subscriber.onError(new IllegalStateException("No fake response enqueued"));
        }
        return subscriber -> {
            subscriber.onSubscribe(new Flow.Subscription() {
                @Override public void request(long n) {}
                @Override public void cancel() {}
            });
            try {
                if (response.toolCalls() != null) {
                    for (ChatResponse.ToolCall tc : response.toolCalls()) {
                        subscriber.onNext(new StreamEvent.ToolCallStart(tc.id(), tc.toolName(), tc.arguments()));
                        subscriber.onNext(new StreamEvent.ToolCallDone(tc.id(), tc.toolName(), tc.arguments()));
                    }
                }
                String content = response.message().content();
                for (int i = 0; i < content.length(); i++) {
                    subscriber.onNext(new StreamEvent.Token(String.valueOf(content.charAt(i)), i, null));
                }
                subscriber.onNext(new StreamEvent.Finish(
                    response.finishReason(),
                    response.tokenCount().inputTokens(),
                    response.tokenCount().outputTokens()
                ));
                subscriber.onComplete();
            } catch (Exception e) {
                subscriber.onError(e);
            }
        };
    }

    @Override
    public ChatResponse complete(ChatRequest request, CancellationToken cancellationToken) {
        ChatResponse response = responses.poll();
        if (response == null) {
            throw new IllegalStateException("No fake response enqueued");
        }
        return response;
    }

    @Override
    public HealthStatus health() {
        return HealthStatus.HEALTHY;
    }

    @Override
    public String providerName() {
        return "fake";
    }
}
