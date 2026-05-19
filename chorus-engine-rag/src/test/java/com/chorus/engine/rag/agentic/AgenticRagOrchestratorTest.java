package com.chorus.engine.rag.agentic;

import com.chorus.engine.core.context.Message;
import com.chorus.engine.core.context.TokenCount;
import com.chorus.engine.core.reactive.CancellationToken;
import com.chorus.engine.llm.*;
import com.chorus.engine.rag.document.Chunk;
import com.chorus.engine.rag.retrieval.RetrievalEngine;
import com.chorus.engine.rag.store.VectorStore;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.Flow;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AgenticRagOrchestratorTest {

    @Test
    void queryWithSinglePassAnswer() {
        FakeLlmClient llm = new FakeLlmClient();
        FakeRetrievalEngine retrieval = new FakeRetrievalEngine();
        FakeVectorStore vectorStore = new FakeVectorStore();

        llm.enqueue(chatResponse("ANSWER: Paris is the capital of France."));

        AgenticRagOrchestrator orchestrator = new AgenticRagOrchestrator(
            llm, "model", vectorStore, retrieval, 5
        );
        AgenticRagOrchestrator.AgenticResult result = orchestrator.execute("What is the capital of France?");

        assertThat(result.answer()).isEqualTo("Paris is the capital of France.");
        assertThat(result.iterationsUsed()).isEqualTo(1);
        assertThat(result.converged()).isTrue();
    }

    @Test
    void queryThatNeedsMultipleIterations() {
        FakeLlmClient llm = new FakeLlmClient();
        FakeRetrievalEngine retrieval = new FakeRetrievalEngine();
        FakeVectorStore vectorStore = new FakeVectorStore();

        retrieval.addResult(chunk("c1", "Paris is in France"), 0.90);
        llm.enqueue(chatResponse("SEARCH: capital city of France"));
        retrieval.addResult(chunk("c2", "Paris is the capital of France"), 0.95);
        llm.enqueue(chatResponse("ANSWER: Paris"));

        AgenticRagOrchestrator orchestrator = new AgenticRagOrchestrator(
            llm, "model", vectorStore, retrieval, 5
        );
        AgenticRagOrchestrator.AgenticResult result = orchestrator.execute("What is the capital of France?");

        assertThat(result.answer()).isEqualTo("Paris");
        assertThat(result.iterationsUsed()).isEqualTo(2);
        assertThat(result.converged()).isTrue();
        assertThat(result.retrieved()).hasSize(2);
    }

    @Test
    void maxIterationsBoundary() {
        FakeLlmClient llm = new FakeLlmClient();
        FakeRetrievalEngine retrieval = new FakeRetrievalEngine();
        FakeVectorStore vectorStore = new FakeVectorStore();

        for (int i = 0; i < 3; i++) {
            retrieval.addResult(chunk("c" + i, "doc " + i), 0.5);
            llm.enqueue(chatResponse("SEARCH: query iteration " + i));
        }
        llm.enqueue(chatResponse("Final fallback answer"));

        AgenticRagOrchestrator orchestrator = new AgenticRagOrchestrator(
            llm, "model", vectorStore, retrieval, 3
        );
        AgenticRagOrchestrator.AgenticResult result = orchestrator.execute("Complex query?");

        assertThat(result.iterationsUsed()).isEqualTo(3);
        assertThat(result.converged()).isFalse();
        assertThat(result.answer()).isEqualTo("Final fallback answer");
    }

    @Test
    void nullRejection() {
        FakeLlmClient llm = new FakeLlmClient();
        FakeRetrievalEngine retrieval = new FakeRetrievalEngine();
        FakeVectorStore vectorStore = new FakeVectorStore();

        AgenticRagOrchestrator orchestrator = new AgenticRagOrchestrator(
            llm, "model", vectorStore, retrieval, 3
        );

        assertThatThrownBy(() -> orchestrator.execute(null))
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

    static class FakeVectorStore implements VectorStore {
        @Override public void upsert(List<Chunk> chunks) {}
        @Override public List<RetrievalResult> search(float[] queryEmbedding, int topK, Map<String, Object> filters) {
            return List.of();
        }
        @Override public void delete(Set<String> chunkIds) {}
        @Override public void deleteByDocument(String documentId) {}
        @Override public long count() { return 0; }
        @Override public String storeName() { return "fake"; }
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
