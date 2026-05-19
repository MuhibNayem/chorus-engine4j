package com.chorus.engine.llm.provider;

import com.chorus.engine.core.context.Message;
import com.chorus.engine.core.reactive.CancellationToken;
import com.chorus.engine.llm.*;
import com.chorus.engine.llm.retry.CircuitBreaker;
import com.chorus.engine.llm.retry.RetryPolicy;
import com.chorus.engine.llm.testutil.FakeHttpClient;
import com.chorus.engine.llm.testutil.FakeHttpResponse;
import com.chorus.engine.llm.testutil.HttpRequestBodyExtractor;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.Flow;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.*;

class OpenAiProviderTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private FakeHttpClient fakeClient;
    private CircuitBreaker circuitBreaker;
    private ExecutorService executor;
    private OpenAiProvider provider;

    @BeforeEach
    void setUp() {
        fakeClient = new FakeHttpClient();
        circuitBreaker = new CircuitBreaker(3, Duration.ofMinutes(1));
        RetryPolicy retryPolicy = new RetryPolicy(
            3, Duration.ZERO, Duration.ZERO, 0.0,
            Set.of(429, 500, 502, 503, 504),
            Set.of(),
            Duration.ofSeconds(30)
        );
        executor = Executors.newSingleThreadExecutor();
        provider = new OpenAiProvider(
            "openai-test",
            "http://localhost:8080",
            "fake-key",
            null,
            fakeClient,
            MAPPER,
            retryPolicy,
            circuitBreaker,
            executor
        );
    }

    @AfterEach
    void tearDown() {
        provider.close();
        executor.shutdown();
    }

    private InputStream sseStream(String... dataLines) {
        StringBuilder sb = new StringBuilder();
        for (String line : dataLines) {
            sb.append("data: ").append(line).append("\n\n");
        }
        return new ByteArrayInputStream(sb.toString().getBytes(StandardCharsets.UTF_8));
    }

    private List<StreamEvent> collectEvents(Flow.Publisher<StreamEvent> publisher) throws Exception {
        List<StreamEvent> events = Collections.synchronizedList(new ArrayList<>());
        CountDownLatch latch = new CountDownLatch(1);
        AtomicReference<Throwable> error = new AtomicReference<>();
        publisher.subscribe(new Flow.Subscriber<>() {
            @Override public void onSubscribe(Flow.Subscription s) { s.request(Long.MAX_VALUE); }
            @Override public void onNext(StreamEvent event) { events.add(event); }
            @Override public void onError(Throwable t) { error.set(t); latch.countDown(); }
            @Override public void onComplete() { latch.countDown(); }
        });
        assertThat(latch.await(5, TimeUnit.SECONDS)).isTrue();
        if (error.get() != null) {
            throw new AssertionError("Stream error", error.get());
        }
        return events;
    }

    @Test
    void complete_with_valid_response() throws Exception {
        ChatRequest request = ChatRequest.builder()
            .model("gpt-4o")
            .messages(List.of(Message.user("Hello")))
            .maxTokens(500)
            .build();

        fakeClient.enqueue(new FakeHttpResponse<>(200, sseStream(
            "{\"choices\":[{\"delta\":{\"content\":\"Hi there\"}}]}",
            "{\"choices\":[{\"delta\":{},\"finish_reason\":\"stop\"}],\"usage\":{\"prompt_tokens\":5,\"completion_tokens\":2}}"
        )));

        ChatResponse response = provider.complete(request, CancellationToken.create());

        assertThat(response.model()).isEqualTo("gpt-4o");
        assertThat(response.provider()).isEqualTo("openai-test");
        assertThat(response.message().content()).isEqualTo("Hi there");
        assertThat(response.finishReason()).isEqualTo("stop");
        assertThat(response.hasToolCalls()).isFalse();
        assertThat(response.tokenCount().inputTokens()).isEqualTo(5);
        assertThat(response.tokenCount().outputTokens()).isEqualTo(2);
    }

    @Test
    void stream_with_sse_events() throws Exception {
        ChatRequest request = ChatRequest.builder()
            .model("gpt-4o")
            .messages(List.of(Message.user("Hello")))
            .maxTokens(500)
            .build();

        fakeClient.enqueue(new FakeHttpResponse<>(200, sseStream(
            "{\"choices\":[{\"delta\":{\"content\":\"Hello\"}}]}",
            "{\"choices\":[{\"delta\":{\"content\":\" world\"}}]}",
            "{\"choices\":[{\"delta\":{},\"finish_reason\":\"stop\"}],\"usage\":{\"prompt_tokens\":3,\"completion_tokens\":2}}"
        )));

        List<StreamEvent> events = collectEvents(provider.stream(request, CancellationToken.create()));

        assertThat(events).hasSize(3);
        assertThat(events.get(0)).isInstanceOf(StreamEvent.Token.class);
        assertThat(((StreamEvent.Token) events.get(0)).token()).isEqualTo("Hello");
        assertThat(events.get(1)).isInstanceOf(StreamEvent.Token.class);
        assertThat(((StreamEvent.Token) events.get(1)).token()).isEqualTo(" world");
        assertThat(events.get(2)).isInstanceOf(StreamEvent.Finish.class);
        StreamEvent.Finish finish = (StreamEvent.Finish) events.get(2);
        assertThat(finish.finishReason()).isEqualTo("stop");
    }

    @Test
    void health_returns_healthy_when_closed() {
        assertThat(provider.health()).isEqualTo(LlmClient.HealthStatus.HEALTHY);
    }

    @Test
    void health_returns_unavailable_when_circuit_open() {
        circuitBreaker.recordFailure();
        circuitBreaker.recordFailure();
        circuitBreaker.recordFailure();
        assertThat(circuitBreaker.isOpen()).isTrue();
        assertThat(provider.health()).isEqualTo(LlmClient.HealthStatus.UNAVAILABLE);
    }

    @Test
    void retry_on_429_then_success() throws Exception {
        ChatRequest request = ChatRequest.builder()
            .model("gpt-4o")
            .messages(List.of(Message.user("Hello")))
            .maxTokens(500)
            .build();

        fakeClient.enqueue(new FakeHttpResponse<>(429,
            new ByteArrayInputStream("rate limited".getBytes(StandardCharsets.UTF_8))));
        fakeClient.enqueue(new FakeHttpResponse<>(200, sseStream(
            "{\"choices\":[{\"delta\":{\"content\":\"OK\"}}]}",
            "{\"choices\":[{\"delta\":{},\"finish_reason\":\"stop\"}],\"usage\":{\"prompt_tokens\":1,\"completion_tokens\":1}}"
        )));

        ChatResponse response = provider.complete(request, CancellationToken.create());
        assertThat(response.message().content()).isEqualTo("OK");
    }

    @Test
    void retry_on_502_then_503_then_success() throws Exception {
        ChatRequest request = ChatRequest.builder()
            .model("gpt-4o")
            .messages(List.of(Message.user("Hello")))
            .maxTokens(500)
            .build();

        fakeClient.enqueue(new FakeHttpResponse<>(502,
            new ByteArrayInputStream("bad gateway".getBytes(StandardCharsets.UTF_8))));
        fakeClient.enqueue(new FakeHttpResponse<>(503,
            new ByteArrayInputStream("service unavailable".getBytes(StandardCharsets.UTF_8))));
        fakeClient.enqueue(new FakeHttpResponse<>(200, sseStream(
            "{\"choices\":[{\"delta\":{\"content\":\"OK\"}}]}",
            "{\"choices\":[{\"delta\":{},\"finish_reason\":\"stop\"}],\"usage\":{\"prompt_tokens\":1,\"completion_tokens\":1}}"
        )));

        ChatResponse response = provider.complete(request, CancellationToken.create());
        assertThat(response.message().content()).isEqualTo("OK");
    }

    @Test
    void circuit_breaker_blocks_when_open() throws Exception {
        ChatRequest request = ChatRequest.builder()
            .model("gpt-4o")
            .messages(List.of(Message.user("Hello")))
            .maxTokens(500)
            .build();

        circuitBreaker.recordFailure();
        circuitBreaker.recordFailure();
        circuitBreaker.recordFailure();
        assertThat(circuitBreaker.isOpen()).isTrue();

        AtomicReference<Throwable> errorRef = new AtomicReference<>();
        CountDownLatch latch = new CountDownLatch(1);
        provider.stream(request, CancellationToken.create()).subscribe(new Flow.Subscriber<>() {
            @Override public void onSubscribe(Flow.Subscription s) { s.request(Long.MAX_VALUE); }
            @Override public void onNext(StreamEvent event) {}
            @Override public void onError(Throwable t) { errorRef.set(t); latch.countDown(); }
            @Override public void onComplete() { latch.countDown(); }
        });

        assertThat(latch.await(1, TimeUnit.SECONDS)).isTrue();
        assertThat(errorRef.get()).isNotNull()
            .isInstanceOf(RuntimeException.class)
            .hasMessageContaining("Circuit breaker is OPEN");
    }

    @Test
    void stream_error_on_non_retryable_non_2xx() throws Exception {
        ChatRequest request = ChatRequest.builder()
            .model("gpt-4o")
            .messages(List.of(Message.user("Hello")))
            .maxTokens(500)
            .build();

        fakeClient.enqueue(new FakeHttpResponse<>(400,
            new ByteArrayInputStream("bad request".getBytes(StandardCharsets.UTF_8))));

        AtomicReference<Throwable> errorRef = new AtomicReference<>();
        CountDownLatch latch = new CountDownLatch(1);
        provider.stream(request, CancellationToken.create()).subscribe(new Flow.Subscriber<>() {
            @Override public void onSubscribe(Flow.Subscription s) { s.request(Long.MAX_VALUE); }
            @Override public void onNext(StreamEvent event) {}
            @Override public void onError(Throwable t) { errorRef.set(t); latch.countDown(); }
            @Override public void onComplete() { latch.countDown(); }
        });

        assertThat(latch.await(1, TimeUnit.SECONDS)).isTrue();
        assertThat(errorRef.get()).isNotNull()
            .isInstanceOf(RuntimeException.class)
            .hasMessageContaining("HTTP 400");
    }

    @Test
    void tool_call_parsing_in_response() throws Exception {
        ChatRequest request = ChatRequest.builder()
            .model("gpt-4o")
            .messages(List.of(Message.user("What's the weather?")))
            .tools(List.of(ToolDefinition.of("get_weather", "Get weather")))
            .maxTokens(500)
            .build();

        fakeClient.enqueue(new FakeHttpResponse<>(200, sseStream(
            "{\"choices\":[{\"delta\":{\"tool_calls\":[{\"id\":\"call_01\",\"function\":{\"name\":\"get_weather\"}}]}}]}",
            "{\"choices\":[{\"delta\":{\"tool_calls\":[{\"index\":0,\"function\":{\"arguments\":\"{\\\"city\\\":\\\"Paris\\\"}\"}}]}}]}",
            "{\"choices\":[{\"delta\":{},\"finish_reason\":\"tool_calls\"}],\"usage\":{\"prompt_tokens\":10,\"completion_tokens\":5}}"
        )));

        ChatResponse response = provider.complete(request, CancellationToken.create());

        assertThat(response.hasToolCalls()).isTrue();
        assertThat(response.toolCalls()).hasSize(1);
        ChatResponse.ToolCall tc = response.toolCalls().get(0);
        assertThat(tc.id()).isEqualTo("call_01");
        assertThat(tc.toolName()).isEqualTo("get_weather");
        // OpenAiProvider.complete() does not accumulate argument deltas into ToolCall arguments
        assertThat(tc.arguments()).isEmpty();
    }

    @Test
    void stream_emits_toolCallStart() throws Exception {
        ChatRequest request = ChatRequest.builder()
            .model("gpt-4o")
            .messages(List.of(Message.user("What's the weather?")))
            .tools(List.of(ToolDefinition.of("get_weather", "Get weather")))
            .maxTokens(500)
            .build();

        fakeClient.enqueue(new FakeHttpResponse<>(200, sseStream(
            "{\"choices\":[{\"delta\":{\"tool_calls\":[{\"id\":\"call_01\",\"function\":{\"name\":\"get_weather\"}}]}}]}",
            "{\"choices\":[{\"delta\":{},\"finish_reason\":\"stop\"}],\"usage\":{\"prompt_tokens\":10,\"completion_tokens\":5}}"
        )));

        List<StreamEvent> events = collectEvents(provider.stream(request, CancellationToken.create()));

        assertThat(events).anyMatch(e -> e instanceof StreamEvent.ToolCallStart);
        StreamEvent.ToolCallStart start = events.stream()
            .filter(e -> e instanceof StreamEvent.ToolCallStart)
            .map(e -> (StreamEvent.ToolCallStart) e)
            .findFirst().orElseThrow();
        assertThat(start.toolCallId()).isEqualTo("call_01");
        assertThat(start.toolName()).isEqualTo("get_weather");
    }

    @Test
    void request_body_construction() throws Exception {
        ChatRequest request = ChatRequest.builder()
            .model("gpt-4o-mini")
            .messages(List.of(
                Message.system("Be helpful"),
                Message.user("Hello")
            ))
            .tools(List.of(ToolDefinition.of("get_weather", "Get weather")))
            .temperature(0.5)
            .maxTokens(256)
            .responseFormat(ResponseFormat.json())
            .stopSequence("STOP")
            .providerExtras(Map.of("top_p", 0.9))
            .build();

        fakeClient.enqueue(new FakeHttpResponse<>(200, sseStream(
            "{\"choices\":[{\"delta\":{\"content\":\"x\"}}]}",
            "{\"choices\":[{\"delta\":{},\"finish_reason\":\"stop\"}],\"usage\":{\"prompt_tokens\":1,\"completion_tokens\":1}}"
        )));

        provider.complete(request, CancellationToken.create());

        String bodyJson = HttpRequestBodyExtractor.extract(fakeClient.lastRequest());
        JsonNode body = MAPPER.readTree(bodyJson);

        assertThat(body.path("model").asText()).isEqualTo("gpt-4o-mini");
        assertThat(body.path("temperature").asDouble()).isEqualTo(0.5);
        assertThat(body.path("max_tokens").asInt()).isEqualTo(256);
        assertThat(body.path("stream").asBoolean()).isTrue();
        assertThat(body.path("stop").asText()).isEqualTo("STOP");
        assertThat(body.path("response_format").path("type").asText()).isEqualTo("json_object");
        assertThat(body.path("top_p").asDouble()).isEqualTo(0.9);

        assertThat(body.path("messages")).hasSize(2);
        assertThat(body.path("messages").get(0).path("role").asText()).isEqualTo("system");
        assertThat(body.path("messages").get(0).path("content").asText()).isEqualTo("Be helpful");
        assertThat(body.path("messages").get(1).path("role").asText()).isEqualTo("user");
        assertThat(body.path("messages").get(1).path("content").asText()).isEqualTo("Hello");

        assertThat(body.path("tools")).hasSize(1);
        assertThat(body.path("tools").get(0).path("type").asText()).isEqualTo("function");
        assertThat(body.path("tools").get(0).path("function").path("name").asText()).isEqualTo("get_weather");

        assertThat(fakeClient.lastRequest().headers().firstValue("Authorization"))
            .hasValue("Bearer fake-key");
    }
}
