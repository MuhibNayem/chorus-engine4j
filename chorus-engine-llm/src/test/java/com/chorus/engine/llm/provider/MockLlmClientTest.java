package com.chorus.engine.llm.provider;

import com.chorus.engine.core.context.Message;
import com.chorus.engine.core.context.Role;
import com.chorus.engine.core.reactive.CancellationToken;
import com.chorus.engine.llm.ChatRequest;
import com.chorus.engine.llm.StreamEvent;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.*;

/**
 * Unit tests for {@link MockLlmClient}.
 */
@DisplayName("MockLlmClient")
class MockLlmClientTest {

    private MockLlmClient client;

    @AfterEach
    void tearDown() {
        if (client != null) client.close();
    }

    // ── Provider contract ─────────────────────────────────────────────

    @Test
    @DisplayName("providerName() returns 'mock'")
    void providerNameIsMock() {
        client = MockLlmClient.defaults();
        assertThat(client.providerName()).isEqualTo("mock");
    }

    @Test
    @DisplayName("health() returns HEALTHY when open")
    void healthIsHealthyWhenOpen() {
        client = MockLlmClient.defaults();
        assertThat(client.health()).isEqualTo(MockLlmClient.HealthStatus.HEALTHY);
    }

    @Test
    @DisplayName("health() returns UNAVAILABLE after close()")
    void healthIsUnavailableAfterClose() {
        client = MockLlmClient.defaults();
        client.close();
        assertThat(client.health()).isEqualTo(MockLlmClient.HealthStatus.UNAVAILABLE);
    }

    // ── Streaming ─────────────────────────────────────────────────────

    @Test
    @DisplayName("stream() emits Token events followed by a Finish event")
    void streamEmitsTokensThenFinish() throws InterruptedException {
        client = new MockLlmClient(
            List.of(MockLlmClient.ResponseScript.text("Hello world test")),
            Duration.ofMillis(0)
        );

        List<StreamEvent> events = collectEvents(client, userMessage("hi"));

        assertThat(events).isNotEmpty();
        assertThat(events.stream().filter(e -> e instanceof StreamEvent.Token).count()).isGreaterThan(0);
        assertThat(events).last().isInstanceOf(StreamEvent.Finish.class);
    }

    @Test
    @DisplayName("stream() emits Finish with stop reason for text responses")
    void finishEventHasStopReason() throws InterruptedException {
        client = new MockLlmClient(
            List.of(MockLlmClient.ResponseScript.text("test response")),
            Duration.ofMillis(0)
        );

        List<StreamEvent> events = collectEvents(client, userMessage("hi"));

        StreamEvent.Finish finish = events.stream()
            .filter(e -> e instanceof StreamEvent.Finish)
            .map(e -> (StreamEvent.Finish) e)
            .findFirst()
            .orElseThrow();

        assertThat(finish.finishReason()).isEqualTo("stop");
    }

    @Test
    @DisplayName("stream() Finish event has non-zero completion tokens")
    void finishEventHasCompletionTokens() throws InterruptedException {
        client = new MockLlmClient(
            List.of(MockLlmClient.ResponseScript.text("one two three four five six words")),
            Duration.ofMillis(0)
        );

        List<StreamEvent> events = collectEvents(client, userMessage("query"));

        StreamEvent.Finish finish = events.stream()
            .filter(e -> e instanceof StreamEvent.Finish)
            .map(e -> (StreamEvent.Finish) e)
            .findFirst()
            .orElseThrow();

        assertThat(finish.completionTokens()).isGreaterThan(0);
    }

    // ── Script selection ──────────────────────────────────────────────

    @Test
    @DisplayName("wildcard trigger '*' matches any message")
    void wildcardTriggerMatchesAny() throws InterruptedException {
        client = new MockLlmClient(
            List.of(MockLlmClient.ResponseScript.text("*", "wildcard matched")),
            Duration.ofMillis(0)
        );

        String text = collectText(client, userMessage("anything at all"));

        assertThat(text).contains("wildcard matched");
    }

    @Test
    @DisplayName("keyword trigger matches substring of user message")
    void keywordTriggerMatchesSubstring() throws InterruptedException {
        client = new MockLlmClient(
            List.of(
                MockLlmClient.ResponseScript.text("summarize", "here is your summary"),
                MockLlmClient.ResponseScript.text("*", "generic response")
            ),
            Duration.ofMillis(0)
        );

        String text = collectText(client, userMessage("please summarize this document"));

        assertThat(text).contains("here is your summary");
    }

    @Test
    @DisplayName("falls through to wildcard when no keyword matches")
    void fallsThroughToWildcard() throws InterruptedException {
        client = new MockLlmClient(
            List.of(
                MockLlmClient.ResponseScript.text("translate", "translation here"),
                MockLlmClient.ResponseScript.text("*", "fallback response")
            ),
            Duration.ofMillis(0)
        );

        String text = collectText(client, userMessage("do something else"));

        assertThat(text).contains("fallback response");
    }

    @Test
    @DisplayName("keyword matching is case-insensitive")
    void keywordMatchingIsCaseInsensitive() throws InterruptedException {
        client = new MockLlmClient(
            List.of(MockLlmClient.ResponseScript.text("HELLO", "case insensitive match")),
            Duration.ofMillis(0)
        );

        String text = collectText(client, userMessage("hello world"));

        assertThat(text).contains("case insensitive match");
    }

    // ── Tool call sequence ────────────────────────────────────────────

    @Test
    @DisplayName("tool-call script emits ToolCallStart → ToolCallDone → Finish(tool_calls)")
    void toolCallEmitsCorrectSequence() throws InterruptedException {
        client = new MockLlmClient(
            List.of(MockLlmClient.ResponseScript.toolCall(
                "*", "search_web", Map.of("query", "Chorus Engine")
            )),
            Duration.ofMillis(0)
        );

        List<StreamEvent> events = collectEvents(client, userMessage("search for Chorus"));

        assertThat(events.stream().anyMatch(e -> e instanceof StreamEvent.ToolCallStart)).isTrue();
        assertThat(events.stream().anyMatch(e -> e instanceof StreamEvent.ToolCallDone)).isTrue();

        StreamEvent.Finish finish = events.stream()
            .filter(e -> e instanceof StreamEvent.Finish)
            .map(e -> (StreamEvent.Finish) e)
            .findFirst()
            .orElseThrow();

        assertThat(finish.finishReason()).isEqualTo("tool_calls");
    }

    @Test
    @DisplayName("tool-call ToolCallDone contains correct tool name")
    void toolCallDoneHasCorrectToolName() throws InterruptedException {
        client = new MockLlmClient(
            List.of(MockLlmClient.ResponseScript.toolCall(
                "*", "weather_api", Map.of("location", "London")
            )),
            Duration.ofMillis(0)
        );

        List<StreamEvent> events = collectEvents(client, userMessage("weather in London"));

        StreamEvent.ToolCallDone done = events.stream()
            .filter(e -> e instanceof StreamEvent.ToolCallDone)
            .map(e -> (StreamEvent.ToolCallDone) e)
            .findFirst()
            .orElseThrow();

        assertThat(done.toolName()).isEqualTo("weather_api");
    }

    // ── Close safety ──────────────────────────────────────────────────

    @Test
    @DisplayName("close() is idempotent — calling twice does not throw")
    void closeIsIdempotent() {
        client = MockLlmClient.defaults();
        assertThatCode(() -> {
            client.close();
            client.close();
        }).doesNotThrowAnyException();
    }

    // ── Helpers ───────────────────────────────────────────────────────

    private static ChatRequest userMessage(String content) {
        return ChatRequest.builder()
            .model("mock-model")
            .messages(List.of(Message.user(content)))
            .temperature(0.7)
            .maxTokens(1024)
            .build();
    }

    private static List<StreamEvent> collectEvents(MockLlmClient client, ChatRequest request)
        throws InterruptedException {
        List<StreamEvent> events = new ArrayList<>();
        CountDownLatch latch = new CountDownLatch(1);
        AtomicReference<Throwable> error = new AtomicReference<>();

        client.stream(request, CancellationToken.create()).subscribe(new Flow.Subscriber<StreamEvent>() {
            private Flow.Subscription subscription;

            @Override public void onSubscribe(Flow.Subscription s) {
                this.subscription = s;
                s.request(Long.MAX_VALUE);
            }

            @Override public void onNext(StreamEvent event) { events.add(event); }

            @Override public void onError(Throwable t) {
                error.set(t);
                latch.countDown();
            }

            @Override public void onComplete() { latch.countDown(); }
        });

        assertThat(latch.await(10, TimeUnit.SECONDS)).isTrue();
        if (error.get() != null) fail("Stream errored: " + error.get().getMessage());
        return events;
    }

    private static String collectText(MockLlmClient client, ChatRequest request)
        throws InterruptedException {
        List<StreamEvent> events = collectEvents(client, request);
        StringBuilder sb = new StringBuilder();
        for (StreamEvent e : events) {
            if (e instanceof StreamEvent.Token t) sb.append(t.token());
        }
        return sb.toString();
    }
}
