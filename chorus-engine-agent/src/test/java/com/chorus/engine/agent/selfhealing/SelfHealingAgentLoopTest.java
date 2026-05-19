package com.chorus.engine.agent.selfhealing;

import com.chorus.engine.agent.loop.AgentLoop;
import com.chorus.engine.core.context.Message;
import com.chorus.engine.core.context.TokenCount;
import com.chorus.engine.core.event.AgentEvent;
import com.chorus.engine.core.reactive.CancellationToken;
import com.chorus.engine.llm.*;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.*;
import java.util.concurrent.Flow;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class SelfHealingAgentLoopTest {

    @Test
    void delegatesEventsToSubscriber() throws InterruptedException {
        AgentLoop delegate = createStubAgentLoop();
        SelfHealingAgentLoop loop = new SelfHealingAgentLoop(delegate, SelfHealingAgentLoop.defaultPolicy());

        CountDownLatch latch = new CountDownLatch(1);
        AtomicInteger eventCount = new AtomicInteger(0);

        loop.run("run-1", "hello", List.of(), CancellationToken.create()).subscribe(new Flow.Subscriber<>() {
            @Override public void onSubscribe(Flow.Subscription s) { s.request(Long.MAX_VALUE); }
            @Override public void onNext(AgentEvent event) { eventCount.incrementAndGet(); }
            @Override public void onError(Throwable t) { latch.countDown(); }
            @Override public void onComplete() { latch.countDown(); }
        });

        assertTrue(latch.await(5, TimeUnit.SECONDS));
        assertTrue(eventCount.get() > 0);
        loop.close();
    }

    @Test
    void detectsRepeatedToolFailures() {
        SelfHealingAgentLoop.SessionState session = new SelfHealingAgentLoop.SessionState("test");

        for (int i = 0; i < 3; i++) {
            session.observe(new AgentEvent.ToolCallError("r", java.time.Instant.now(),
                "tool", "connection refused", false, 0));
        }

        SelfHealingAgentLoop.FailurePattern pattern = session.detectPattern();
        assertEquals(SelfHealingAgentLoop.FailurePattern.REPEATED_TOOL_FAILURES, pattern);
    }

    @Test
    void detectsTimeoutStorm() {
        SelfHealingAgentLoop.SessionState session = new SelfHealingAgentLoop.SessionState("test");

        for (int i = 0; i < 2; i++) {
            session.observe(new AgentEvent.ToolCallError("r", java.time.Instant.now(),
                "tool", "timeout after 30s", false, 0));
        }

        SelfHealingAgentLoop.FailurePattern pattern = session.detectPattern();
        assertEquals(SelfHealingAgentLoop.FailurePattern.TIMEOUT_STORM, pattern);
    }

    @Test
    void detectsHallucination() {
        SelfHealingAgentLoop.SessionState session = new SelfHealingAgentLoop.SessionState("test");

        for (int i = 0; i < 2; i++) {
            session.observe(new AgentEvent.Error("r", java.time.Instant.now(),
                "TOOL_ERROR", "unknown tool 'fake_tool'", null, false));
        }

        SelfHealingAgentLoop.FailurePattern pattern = session.detectPattern();
        assertEquals(SelfHealingAgentLoop.FailurePattern.HALLUCINATION, pattern);
    }

    @Test
    void defaultPolicyReturnsHealActions() {
        SelfHealingAgentLoop.HealPolicy policy = SelfHealingAgentLoop.defaultPolicy();
        SelfHealingAgentLoop.SessionState session = new SelfHealingAgentLoop.SessionState("test");

        for (int i = 0; i < 3; i++) {
            session.observe(new AgentEvent.ToolCallError("r", java.time.Instant.now(),
                "t", "err", false, 0));
        }

        SelfHealingAgentLoop.FailurePattern pattern = session.detectPattern();
        assertNotNull(pattern);

        SelfHealingAgentLoop.HealAction action = policy.resolve(pattern, session);
        assertNotNull(action);
        assertNotNull(action.extraInstruction());
        assertEquals(SelfHealingAgentLoop.FailurePattern.REPEATED_TOOL_FAILURES, action.pattern());
    }

    @Test
    void healResetsCounters() {
        SelfHealingAgentLoop.SessionState session = new SelfHealingAgentLoop.SessionState("test");

        for (int i = 0; i < 3; i++) {
            session.observe(new AgentEvent.ToolCallError("r", java.time.Instant.now(),
                "t", "err", false, 0));
        }

        SelfHealingAgentLoop.HealAction action = new SelfHealingAgentLoop.HealAction(
            SelfHealingAgentLoop.FailurePattern.REPEATED_TOOL_FAILURES,
            null, 0.1, null, "Fix it", null);
        session.applyHeal(action);

        assertNull(session.detectPattern());
    }

    private AgentLoop createStubAgentLoop() {
        return new AgentLoop(
            "test-agent", "You are a test agent",
            new StubLlmClient(), "test-model",
            0.7, 1024, 5, List.of(), null,
            Executors.newSingleThreadExecutor()
        );
    }

    static class StubLlmClient implements LlmClient {
        @Override public Flow.Publisher<StreamEvent> stream(ChatRequest request, CancellationToken token) {
            return subscriber -> {
                subscriber.onSubscribe(new Flow.Subscription() {
                    @Override public void request(long n) {}
                    @Override public void cancel() {}
                });
                subscriber.onNext(new StreamEvent.Token("Hello", 0, null));
                subscriber.onNext(new StreamEvent.Finish("stop", 10, 5));
                subscriber.onComplete();
            };
        }
        @Override public ChatResponse complete(ChatRequest request, CancellationToken token) {
            return new ChatResponse("resp-1", "test-model", "stub",
                Message.assistant("Hello"), new TokenCount(10, 5, "test-model"),
                Duration.ZERO, "stop", null, null, Map.of());
        }
        @Override public HealthStatus health() { return HealthStatus.HEALTHY; }
        @Override public String providerName() { return "stub"; }
    }
}
