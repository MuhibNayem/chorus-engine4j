package com.chorus.engine.rag.streaming;

import com.chorus.engine.core.context.Message;
import com.chorus.engine.core.reactive.CancellationToken;
import com.chorus.engine.llm.*;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Flow;

import static org.junit.jupiter.api.Assertions.*;

class GenerationControllerTest {

    @Test
    void generatesTokensAndCountsThem() {
        GenerationController ctrl = new GenerationController(
            "gen-1", new SlowTokenLlmClient(3), "model", 0.3, 100);

        List<RagStreamEvent> events = new ArrayList<>();
        boolean completed = ctrl.start("What is AI?", "Context here",
            CancellationToken.create(), events::add);

        assertTrue(completed, "Generation should complete normally");
        long tokenCount = events.stream().filter(e -> e instanceof RagStreamEvent.Token).count();
        assertEquals(3, tokenCount);
        assertEquals(3, ctrl.tokensEmitted());
        assertEquals("stop", ctrl.finishReason());
    }

    @Test
    void emitsGenerationCompletedEvent() {
        GenerationController ctrl = new GenerationController(
            "gen-2", new SlowTokenLlmClient(2), "model", 0.3, 100);

        List<RagStreamEvent> events = new ArrayList<>();
        ctrl.start("Q", "Ctx", CancellationToken.create(), events::add);

        assertTrue(events.stream().anyMatch(e -> e instanceof RagStreamEvent.GenerationCompleted),
            "Should emit GenerationCompleted");
    }

    @Test
    void respectsCancellationToken() {
        CancellationToken token = CancellationToken.create();
        token.cancel("Test cancel");

        GenerationController ctrl = new GenerationController(
            "gen-3", new SlowTokenLlmClient(5), "model", 0.3, 100);

        List<RagStreamEvent> events = new ArrayList<>();
        boolean completed = ctrl.start("Q", "Ctx", token, events::add);

        assertFalse(completed, "Should not complete when cancelled");
    }

    // ---- Stub LLM client ----

    static class SlowTokenLlmClient implements LlmClient {
        private final int tokenCount;

        SlowTokenLlmClient(int tokenCount) { this.tokenCount = tokenCount; }

        @Override
        public Flow.Publisher<StreamEvent> stream(ChatRequest request, CancellationToken cancellationToken) {
            return subscriber -> {
                subscriber.onSubscribe(new Flow.Subscription() {
                    @Override public void request(long n) {}
                    @Override public void cancel() {}
                });
                for (int i = 0; i < tokenCount; i++) {
                    if (cancellationToken.isCancelled()) break;
                    subscriber.onNext(new StreamEvent.Token("tok" + i, i, null));
                }
                subscriber.onNext(new StreamEvent.Finish("stop", 10, tokenCount));
                subscriber.onComplete();
            };
        }

        @Override public ChatResponse complete(ChatRequest request, CancellationToken cancellationToken) {
            return new ChatResponse("r", "m", "stub", Message.assistant("hello"),
                new com.chorus.engine.core.context.TokenCount(10, tokenCount, "m"),
                Duration.ZERO, "stop", null, null, Map.of());
        }
        @Override public HealthStatus health() { return HealthStatus.HEALTHY; }
        @Override public String providerName() { return "stub-slow"; }
    }
}
