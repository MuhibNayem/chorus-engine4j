package com.chorus.engine.rag.corrective;

import com.chorus.engine.core.context.Message;
import com.chorus.engine.core.context.TokenCount;
import com.chorus.engine.core.reactive.CancellationToken;
import com.chorus.engine.llm.*;
import com.chorus.engine.rag.document.Chunk;
import com.chorus.engine.rag.retrieval.RetrievalEngine;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.Flow;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CorrectiveRagEngineTest {

    @Test
    void queryWithHighQualityAnswerNoCorrection() {
        FakeRetrievalEngine retrieval = new FakeRetrievalEngine();
        FakeLlmClient llm = new FakeLlmClient();
        retrieval.addResult(chunk("c1", "Paris is the capital of France"), 0.95);
        llm.enqueue(chatResponse("SCORE: 8 ACTION: USE REASON: Relevant documents"));

        CorrectiveRagEngine engine = new CorrectiveRagEngine(llm, "model", retrieval, 0.5);
        CorrectiveRagEngine.CorrectiveResult result = engine.execute("What is the capital of France?");

        assertThat(result.finalAction()).isEqualTo(CorrectiveRagEngine.Action.USE);
        assertThat(result.grade().action()).isEqualTo(CorrectiveRagEngine.Action.USE);
        assertThat(result.finalRetrieved()).hasSize(1);
        assertThat(result.fallbackReason()).isEmpty();
    }

    @Test
    void queryWithLowQualityAnswerTriggersCorrection() {
        FakeRetrievalEngine retrieval = new FakeRetrievalEngine();
        FakeLlmClient llm = new FakeLlmClient();
        retrieval.addResult(chunk("c1", "Some irrelevant text"), 0.30);
        llm.enqueue(chatResponse("SCORE: 3 ACTION: USE REASON: Poor coverage"));
        llm.enqueue(chatResponse("better search query"));
        retrieval.addResult(chunk("c2", "Paris is the capital of France"), 0.92);

        CorrectiveRagEngine engine = new CorrectiveRagEngine(llm, "model", retrieval, 0.5);
        CorrectiveRagEngine.CorrectiveResult result = engine.execute("What is the capital of France?");

        assertThat(result.finalAction()).isEqualTo(CorrectiveRagEngine.Action.REFORMULATE);
        assertThat(result.grade().action()).isEqualTo(CorrectiveRagEngine.Action.REFORMULATE);
        assertThat(result.fallbackReason()).isEqualTo("better search query");
    }

    @Test
    void qualityThresholdBoundary() {
        FakeRetrievalEngine retrieval = new FakeRetrievalEngine();
        FakeLlmClient llm = new FakeLlmClient();
        retrieval.addResult(chunk("c1", "Paris is the capital of France"), 0.50);
        // Score exactly at threshold boundary: 5.0 -> normalized 0.5, threshold 0.5
        llm.enqueue(chatResponse("SCORE: 5 ACTION: USE REASON: Borderline"));

        CorrectiveRagEngine engine = new CorrectiveRagEngine(llm, "model", retrieval, 0.5);
        CorrectiveRagEngine.CorrectiveResult result = engine.execute("What is the capital of France?");

        assertThat(result.grade().qualityScore()).isCloseTo(0.5, org.assertj.core.api.Assertions.within(0.01));
        assertThat(result.finalAction()).isEqualTo(CorrectiveRagEngine.Action.USE);
    }

    @Test
    void nullRejection() {
        FakeRetrievalEngine retrieval = new FakeRetrievalEngine();
        FakeLlmClient llm = new FakeLlmClient();
        CorrectiveRagEngine engine = new CorrectiveRagEngine(llm, "model", retrieval, 0.5);

        assertThatThrownBy(() -> engine.execute(null))
            .isInstanceOf(NullPointerException.class);
    }

    // ---- fakes ----

    static class FakeLlmClient implements LlmClient {
        private final Queue<ChatResponse> responses = new ConcurrentLinkedQueue<>();

        void enqueue(ChatResponse response) {
            responses.add(response);
        }

        @Override
        public Flow.Publisher<StreamEvent> stream(ChatRequest request, CancellationToken cancellationToken) {
            throw new UnsupportedOperationException();
        }

        @Override
        public ChatResponse complete(ChatRequest request, CancellationToken cancellationToken) {
            ChatResponse response = responses.poll();
            if (response == null) {
                throw new IllegalStateException("No fake response enqueued");
            }
            return response;
        }

        @Override public HealthStatus health() { return HealthStatus.HEALTHY; }
        @Override public String providerName() { return "fake"; }
    }

    static class FakeRetrievalEngine implements RetrievalEngine {
        private final List<RetrievalResult> results = new ArrayList<>();

        void addResult(Chunk chunk, double score) {
            results.add(new RetrievalResult(chunk, score, "dense"));
        }

        @Override
        public List<RetrievalResult> retrieve(String query, RetrieveOptions options) {
            if (query == null) throw new NullPointerException("query cannot be null");
            return new ArrayList<>(results);
        }
    }

    private ChatResponse chatResponse(String content) {
        return new ChatResponse(
            "resp-1", "test-model", "fake",
            Message.assistant(content),
            new TokenCount(10, content.length(), "test"),
            Duration.ZERO,
            "stop", null, null, Map.of()
        );
    }

    private Chunk chunk(String id, String text) {
        return new Chunk(id, "doc-1", text, 0, text.length(), null, Map.of());
    }
}
