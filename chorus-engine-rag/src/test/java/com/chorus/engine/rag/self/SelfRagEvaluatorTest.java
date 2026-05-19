package com.chorus.engine.rag.self;

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

class SelfRagEvaluatorTest {

    @Test
    void evaluateWithRelevantRetrievedDocs() {
        FakeLlmClient llm = new FakeLlmClient();
        FakeRetrievalEngine retrieval = new FakeRetrievalEngine();

        List<RetrievalEngine.RetrievalResult> initial = List.of(
            result(chunk("c1", "Paris is the capital of France"), 0.95),
            result(chunk("c2", "France is in Europe"), 0.90),
            result(chunk("c3", "Paris has the Eiffel Tower"), 0.88)
        );

        // Grade all 3 as RELEVANT
        llm.enqueue(chatResponse("RELEVANT"));
        llm.enqueue(chatResponse("RELEVANT"));
        llm.enqueue(chatResponse("RELEVANT"));

        SelfRagEvaluator evaluator = new SelfRagEvaluator(llm, "model", retrieval, 2, 0.5);
        SelfRagEvaluator.SelfRagResult result = evaluator.evaluateAndRefine(
            "What is the capital of France?", initial
        );

        assertThat(result.rounds()).hasSize(1);
        assertThat(result.rounds().get(0).relevantCount()).isEqualTo(3);
        assertThat(result.finalResults()).hasSize(3);
    }

    @Test
    void evaluateWithIrrelevantDocsTriggersRefinement() {
        FakeLlmClient llm = new FakeLlmClient();
        FakeRetrievalEngine retrieval = new FakeRetrievalEngine();

        List<RetrievalEngine.RetrievalResult> initial = List.of(
            result(chunk("c1", "Some random text"), 0.30),
            result(chunk("c2", "Another unrelated doc"), 0.25)
        );

        // Round 0: grade 2 chunks as IRRELEVANT
        llm.enqueue(chatResponse("IRRELEVANT"));
        llm.enqueue(chatResponse("IRRELEVANT"));
        // Reformulate query
        llm.enqueue(chatResponse("capital city of France"));
        // Re-retrieve results
        retrieval.addResultSet(List.of(
            result(chunk("c3", "Paris is the capital of France"), 0.95),
            result(chunk("c4", "France's capital is Paris"), 0.92),
            result(chunk("c5", "Paris hosts the government"), 0.90)
        ));
        // Round 1: grade 5 chunks as RELEVANT
        llm.enqueue(chatResponse("RELEVANT"));
        llm.enqueue(chatResponse("RELEVANT"));
        llm.enqueue(chatResponse("RELEVANT"));
        llm.enqueue(chatResponse("RELEVANT"));
        llm.enqueue(chatResponse("RELEVANT"));

        SelfRagEvaluator evaluator = new SelfRagEvaluator(llm, "model", retrieval, 2, 0.5);
        SelfRagEvaluator.SelfRagResult result = evaluator.evaluateAndRefine(
            "What is the capital of France?", initial
        );

        assertThat(result.rounds()).hasSize(2);
        assertThat(result.rounds().get(0).relevantCount()).isEqualTo(0);
        assertThat(result.rounds().get(1).relevantCount()).isEqualTo(5);
        assertThat(result.finalResults()).hasSize(5);
    }

    @Test
    void maxRefinementsBoundary() {
        FakeLlmClient llm = new FakeLlmClient();
        FakeRetrievalEngine retrieval = new FakeRetrievalEngine();

        List<RetrievalEngine.RetrievalResult> initial = List.of(
            result(chunk("c1", "Random text"), 0.20)
        );

        // Round 0: grade 1 chunk as IRRELEVANT
        llm.enqueue(chatResponse("IRRELEVANT"));
        // Reformulate
        llm.enqueue(chatResponse("better query"));
        // Re-retrieve results
        retrieval.addResultSet(List.of(
            result(chunk("c2", "Another random text"), 0.20)
        ));
        // Round 1: grade 2 chunks as IRRELEVANT, but round == maxRefinements -> break
        llm.enqueue(chatResponse("IRRELEVANT"));
        llm.enqueue(chatResponse("IRRELEVANT"));

        SelfRagEvaluator evaluator = new SelfRagEvaluator(llm, "model", retrieval, 1, 0.5);
        SelfRagEvaluator.SelfRagResult result = evaluator.evaluateAndRefine(
            "What is the capital of France?", initial
        );

        assertThat(result.rounds()).hasSize(2);
        assertThat(result.rounds().get(0).relevantCount()).isEqualTo(0);
        assertThat(result.rounds().get(1).relevantCount()).isEqualTo(0);
    }

    @Test
    void nullRejection() {
        FakeLlmClient llm = new FakeLlmClient();
        FakeRetrievalEngine retrieval = new FakeRetrievalEngine();

        SelfRagEvaluator evaluator = new SelfRagEvaluator(llm, "model", retrieval, 2, 0.5);

        assertThatThrownBy(() -> evaluator.evaluateAndRefine(null, List.of()))
            .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> evaluator.evaluateAndRefine("query", null))
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
                throw new IllegalStateException("No fake response enqueued. Needed " + (responses.size() + 1) + " more.");
            }
            return response;
        }

        @Override public HealthStatus health() { return HealthStatus.HEALTHY; }
        @Override public String providerName() { return "fake"; }
    }

    static class FakeRetrievalEngine implements RetrievalEngine {
        private final List<List<RetrievalResult>> resultSets = new ArrayList<>();
        private int callIndex = 0;

        void addResultSet(List<RetrievalResult> results) {
            resultSets.add(results);
        }

        @Override
        public List<RetrievalResult> retrieve(String query, RetrieveOptions options) {
            if (query == null) throw new NullPointerException("query cannot be null");
            if (resultSets.isEmpty()) return List.of();
            List<RetrievalResult> results = resultSets.get(Math.min(callIndex, resultSets.size() - 1));
            callIndex++;
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

    private RetrievalEngine.RetrievalResult result(Chunk chunk, double score) {
        return new RetrievalEngine.RetrievalResult(chunk, score, "dense");
    }
}
