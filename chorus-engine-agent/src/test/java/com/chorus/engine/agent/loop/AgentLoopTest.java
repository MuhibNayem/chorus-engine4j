package com.chorus.engine.agent.loop;

import com.chorus.engine.agent.hitl.HitlGate;
import com.chorus.engine.agent.middleware.Middleware;
import com.chorus.engine.core.context.Message;
import com.chorus.engine.core.context.TokenCount;
import com.chorus.engine.core.event.AgentEvent;
import com.chorus.engine.core.reactive.CancellationToken;
import com.chorus.engine.core.result.Result;
import com.chorus.engine.llm.*;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.Flow;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AgentLoopTest {

    @Test
    void runWithSimpleCompletionNoTools() throws InterruptedException {
        FakeLlmClient fakeLlm = new FakeLlmClient();
        fakeLlm.enqueue(chatResponse("Hello there", null));

        AgentLoop loop = createAgentLoop(fakeLlm, 5);
        List<AgentEvent> events = collectEvents(loop.run("run-1", "hi", List.of(), CancellationToken.create()));
        loop.close();

        assertThat(events).anyMatch(e -> e instanceof AgentEvent.StreamToken t && t.token().equals("H"));
        assertThat(events).anyMatch(e -> e instanceof AgentEvent.Done);
        assertThat(events).noneMatch(e -> e instanceof AgentEvent.ToolCallStart);
    }

    @Test
    void runWithToolExecution() throws InterruptedException {
        FakeLlmClient fakeLlm = new FakeLlmClient();
        fakeLlm.enqueue(chatResponseWithToolCall("call_tool", "weather", Map.of("city", "Paris")));
        fakeLlm.enqueue(chatResponse("The weather is sunny", null));

        AgentLoop loop = createAgentLoop(fakeLlm, 5);
        List<AgentEvent> events = collectEvents(loop.run("run-1", "What's the weather?", List.of(), CancellationToken.create()));
        loop.close();

        assertThat(events).anyMatch(e -> e instanceof AgentEvent.ToolCallStart t && t.toolName().equals("weather"));
        assertThat(events).anyMatch(e -> e instanceof AgentEvent.ToolCallDone);
        assertThat(events).anyMatch(e -> e instanceof AgentEvent.Done d && d.finalAnswer().equals("The weather is sunny"));
    }

    @Test
    void runWithMultipleRounds() throws InterruptedException {
        FakeLlmClient fakeLlm = new FakeLlmClient();
        fakeLlm.enqueue(chatResponseWithToolCall("tc1", "search", Map.of("q", "a")));
        fakeLlm.enqueue(chatResponseWithToolCall("tc2", "search", Map.of("q", "b")));
        fakeLlm.enqueue(chatResponse("Final answer", null));

        AgentLoop loop = createAgentLoop(fakeLlm, 5);
        List<AgentEvent> events = collectEvents(loop.run("run-1", "query", List.of(), CancellationToken.create()));
        loop.close();

        long roundStarts = events.stream().filter(e -> e instanceof AgentEvent.RoundStart).count();
        assertThat(roundStarts).isEqualTo(3);
        assertThat(events).anyMatch(e -> e instanceof AgentEvent.Done);
    }

    @Test
    void runWithMaxRoundsBoundary() throws InterruptedException {
        FakeLlmClient fakeLlm = new FakeLlmClient();
        for (int i = 0; i < 5; i++) {
            fakeLlm.enqueue(chatResponseWithToolCall("tc" + i, "search", Map.of("q", i)));
        }

        AgentLoop loop = createAgentLoop(fakeLlm, 3);
        List<AgentEvent> events = collectEvents(loop.run("run-1", "query", List.of(), CancellationToken.create()));
        loop.close();

        assertThat(events).anyMatch(e -> e instanceof AgentEvent.Error err && err.errorType().equals("MAX_ROUNDS"));
    }

    @Test
    void cancellationMidRun() throws InterruptedException {
        FakeLlmClient fakeLlm = new FakeLlmClient();
        fakeLlm.enqueue(chatResponseWithToolCall("tc1", "search", Map.of("q", "a")));

        AgentLoop loop = createAgentLoop(fakeLlm, 5);
        CancellationToken token = CancellationToken.create();

        List<AgentEvent> events = new CopyOnWriteArrayList<>();
        CountDownLatch latch = new CountDownLatch(1);
        loop.run("run-1", "query", List.of(), token).subscribe(new Flow.Subscriber<>() {
            @Override public void onSubscribe(Flow.Subscription s) { s.request(Long.MAX_VALUE); }
            @Override public void onNext(AgentEvent event) {
                events.add(event);
                if (events.size() == 3) {
                    token.cancel("user-cancelled");
                }
            }
            @Override public void onError(Throwable t) { latch.countDown(); }
            @Override public void onComplete() { latch.countDown(); }
        });

        assertThat(latch.await(2, TimeUnit.SECONDS)).isTrue();
        loop.close();

        assertThat(events).anyMatch(e -> e instanceof AgentEvent.Error err && err.errorType().equals("CANCELLED"));
    }

    @Test
    void middlewareChainIntegration() throws InterruptedException {
        FakeLlmClient fakeLlm = new FakeLlmClient();
        fakeLlm.enqueue(chatResponse("Answer", null));

        AtomicReference<String> extraPrompt = new AtomicReference<>();
        Middleware trackingMw = new Middleware() {
            @Override public int priority() { return 1; }
            @Override public Result<String, MiddlewareError> extraSystemPrompt(String runId, List<Message> history, Map<String, Object> context) {
                extraPrompt.set("injected");
                return Result.ok("injected");
            }
            @Override public Result<Void, MiddlewareError> afterRound(String runId, List<Message> history, String assistantOutput, Map<String, Object> context) {
                return new Result.Ok<>(null);
            }
        };

        AgentLoop loop = createAgentLoop(fakeLlm, 5, List.of(trackingMw));
        List<AgentEvent> events = collectEvents(loop.run("run-1", "hi", List.of(), CancellationToken.create()));
        loop.close();

        assertThat(extraPrompt.get()).isEqualTo("injected");
        assertThat(events).anyMatch(e -> e instanceof AgentEvent.Done);
    }

    @Test
    void hitlGateIntegration() throws InterruptedException {
        FakeLlmClient fakeLlm = new FakeLlmClient();
        fakeLlm.enqueue(chatResponseWithToolCall("tc1", "shell_exec", Map.of("cmd", "rm -rf /")));
        fakeLlm.enqueue(chatResponse("Done", null));

        HitlGate hitlGate = new HitlGate();
        AgentLoop loop = createAgentLoop(fakeLlm, 5, hitlGate);

        List<AgentEvent> events = new CopyOnWriteArrayList<>();
        CountDownLatch latch = new CountDownLatch(1);
        loop.run("run-1", "run dangerous command", List.of(), CancellationToken.create()).subscribe(new Flow.Subscriber<>() {
            @Override public void onSubscribe(Flow.Subscription s) { s.request(Long.MAX_VALUE); }
            @Override public void onNext(AgentEvent event) {
                events.add(event);
                if (event instanceof AgentEvent.HitlRequested) {
                    // Approve gate asynchronously once it has been registered
                    CompletableFuture.runAsync(() -> {
                        String gateId = null;
                        int attempts = 0;
                        while (gateId == null && attempts < 200) {
                            gateId = findFirstGateId(hitlGate);
                            attempts++;
                            if (gateId == null) {
                                try { Thread.sleep(10); } catch (InterruptedException ignored) {}
                            }
                        }
                        if (gateId != null) {
                            hitlGate.approve(gateId);
                        }
                    });
                }
            }
            @Override public void onError(Throwable t) { latch.countDown(); }
            @Override public void onComplete() { latch.countDown(); }
        });

        assertThat(latch.await(5, TimeUnit.SECONDS)).isTrue();
        loop.close();

        assertThat(events).anyMatch(e -> e instanceof AgentEvent.HitlRequested);
        assertThat(events).anyMatch(e -> e instanceof AgentEvent.HitlResolved hr && hr.decision() == AgentEvent.HitlDecision.APPROVE);
        assertThat(events).anyMatch(e -> e instanceof AgentEvent.Done);
    }

    @Test
    void streamingEventsEmittedCorrectly() throws InterruptedException {
        FakeLlmClient fakeLlm = new FakeLlmClient();
        fakeLlm.enqueue(chatResponse("Hi", null));

        AgentLoop loop = createAgentLoop(fakeLlm, 5);
        List<AgentEvent> events = collectEvents(loop.run("run-1", "hello", List.of(), CancellationToken.create()));
        loop.close();

        assertThat(events).anyMatch(e -> e instanceof AgentEvent.StreamStart);
        assertThat(events).anyMatch(e -> e instanceof AgentEvent.RoundStart);
        assertThat(events).anyMatch(e -> e instanceof AgentEvent.StreamToken);
        assertThat(events).anyMatch(e -> e instanceof AgentEvent.RoundEnd);
        assertThat(events).anyMatch(e -> e instanceof AgentEvent.Done);
    }

    @Test
    void emptyUserInput() throws InterruptedException {
        FakeLlmClient fakeLlm = new FakeLlmClient();
        fakeLlm.enqueue(chatResponse("OK", null));

        AgentLoop loop = createAgentLoop(fakeLlm, 5);
        List<AgentEvent> events = collectEvents(loop.run("run-1", "", List.of(), CancellationToken.create()));
        loop.close();

        assertThat(events).anyMatch(e -> e instanceof AgentEvent.Done);
    }

    @Test
    void nullRejection() {
        FakeLlmClient fakeLlm = new FakeLlmClient();
        AgentLoop loop = createAgentLoop(fakeLlm, 5);

        assertThatThrownBy(() -> loop.run(null, "hi", List.of(), CancellationToken.create()))
            .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> loop.run("run-1", null, List.of(), CancellationToken.create()))
            .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> loop.run("run-1", "hi", null, CancellationToken.create()))
            .isInstanceOf(NullPointerException.class);

        loop.close();
    }

    // ---- helpers ----

    private AgentLoop createAgentLoop(FakeLlmClient llm, int maxRounds) {
        return createAgentLoop(llm, maxRounds, List.of());
    }

    private AgentLoop createAgentLoop(FakeLlmClient llm, int maxRounds, List<Middleware> middlewares) {
        return createAgentLoop(llm, maxRounds, middlewares, null);
    }

    private AgentLoop createAgentLoop(FakeLlmClient llm, int maxRounds, HitlGate hitlGate) {
        return createAgentLoop(llm, maxRounds, List.of(), hitlGate);
    }

    private AgentLoop createAgentLoop(FakeLlmClient llm, int maxRounds, List<Middleware> middlewares, HitlGate hitlGate) {
        return new AgentLoop(
            "test-agent",
            "You are a test agent",
            llm,
            "test-model",
            0.7,
            1024,
            maxRounds,
            middlewares,
            hitlGate,
            Executors.newSingleThreadExecutor()
        );
    }

    private String findFirstGateId(HitlGate gate) {
        try {
            java.lang.reflect.Field field = HitlGate.class.getDeclaredField("gates");
            field.setAccessible(true);
            @SuppressWarnings("unchecked")
            Map<String, ?> gates = (Map<String, ?>) field.get(gate);
            return gates.isEmpty() ? null : gates.keySet().iterator().next();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private List<AgentEvent> collectEvents(Flow.Publisher<AgentEvent> publisher) throws InterruptedException {
        List<AgentEvent> events = new CopyOnWriteArrayList<>();
        CountDownLatch latch = new CountDownLatch(1);
        publisher.subscribe(new Flow.Subscriber<>() {
            @Override public void onSubscribe(Flow.Subscription s) { s.request(Long.MAX_VALUE); }
            @Override public void onNext(AgentEvent event) { events.add(event); }
            @Override public void onError(Throwable t) { latch.countDown(); }
            @Override public void onComplete() { latch.countDown(); }
        });
        assertThat(latch.await(5, TimeUnit.SECONDS)).isTrue();
        return events;
    }

    private ChatResponse chatResponse(String content, List<ChatResponse.ToolCall> toolCalls) {
        return new ChatResponse(
            "resp-1", "test-model", "fake",
            Message.assistant(content),
            new TokenCount(10, content.length(), "test"),
            Duration.ZERO,
            "stop",
            toolCalls,
            null,
            Map.of()
        );
    }

    private ChatResponse chatResponseWithToolCall(String id, String toolName, Map<String, Object> args) {
        return chatResponse("Using tool", List.of(new ChatResponse.ToolCall(id, toolName, args)));
    }

    static class FakeLlmClient implements LlmClient {
        private final Queue<ChatResponse> responses = new ConcurrentLinkedQueue<>();

        void enqueue(ChatResponse response) {
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
}
