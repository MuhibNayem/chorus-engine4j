package com.chorus.engine.rag.rerank;

import com.chorus.engine.core.context.Message;
import com.chorus.engine.core.context.TokenCount;
import com.chorus.engine.core.reactive.CancellationToken;
import com.chorus.engine.llm.ChatRequest;
import com.chorus.engine.llm.ChatResponse;
import com.chorus.engine.llm.LlmClient;
import com.chorus.engine.llm.StreamEvent;
import com.chorus.engine.rag.document.Chunk;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.Executor;
import java.util.concurrent.Flow;

import static org.assertj.core.api.Assertions.*;

class LlmJudgeRerankerTest {

    private static final Executor SYNC_EXECUTOR = command -> command.run();

    @Test
    void rerank_ordersChunksByRelevance() {
        FakeLlmClient fake = new FakeLlmClient();
        fake.enqueue(scoreResponse("8"));  // c1
        fake.enqueue(scoreResponse("3"));  // c2
        fake.enqueue(scoreResponse("9"));  // c3

        LlmJudgeReranker reranker = new LlmJudgeReranker(fake, "model", SYNC_EXECUTOR);

        Chunk c1 = chunk("c1", "relevant text about java");
        Chunk c2 = chunk("c2", "somewhat relevant");
        Chunk c3 = chunk("c3", "most relevant java programming");

        List<Reranker.RankedResult> results = reranker.rerank("java programming", List.of(c1, c2, c3), 3);

        assertThat(results).hasSize(3);
        assertThat(results.get(0).chunk().id()).isEqualTo("c3");
        assertThat(results.get(0).relevanceScore()).isEqualTo(0.9);
        assertThat(results.get(1).chunk().id()).isEqualTo("c1");
        assertThat(results.get(1).relevanceScore()).isEqualTo(0.8);
        assertThat(results.get(2).chunk().id()).isEqualTo("c2");
        assertThat(results.get(2).relevanceScore()).isEqualTo(0.3);
    }

    @Test
    void rerank_withEmptyChunks_returnsEmptyList() {
        FakeLlmClient fake = new FakeLlmClient();
        LlmJudgeReranker reranker = new LlmJudgeReranker(fake, "model", SYNC_EXECUTOR);

        List<Reranker.RankedResult> results = reranker.rerank("query", List.of(), 5);

        assertThat(results).isEmpty();
        assertThat(fake.callCount).isZero();
    }

    @Test
    void rerank_withSingleChunk_returnsSingleResult() {
        FakeLlmClient fake = new FakeLlmClient();
        fake.enqueue(scoreResponse("7"));

        LlmJudgeReranker reranker = new LlmJudgeReranker(fake, "model", SYNC_EXECUTOR);
        Chunk c1 = chunk("c1", "only chunk");

        List<Reranker.RankedResult> results = reranker.rerank("query", List.of(c1), 5);

        assertThat(results).hasSize(1);
        assertThat(results.get(0).chunk().id()).isEqualTo("c1");
        assertThat(results.get(0).relevanceScore()).isEqualTo(0.7);
    }

    @Test
    void rerank_limitsTopN() {
        FakeLlmClient fake = new FakeLlmClient();
        fake.enqueue(scoreResponse("5"));
        fake.enqueue(scoreResponse("8"));
        fake.enqueue(scoreResponse("3"));

        LlmJudgeReranker reranker = new LlmJudgeReranker(fake, "model", SYNC_EXECUTOR);
        Chunk c1 = chunk("c1", "text1");
        Chunk c2 = chunk("c2", "text2");
        Chunk c3 = chunk("c3", "text3");

        List<Reranker.RankedResult> results = reranker.rerank("query", List.of(c1, c2, c3), 2);

        assertThat(results).hasSize(2);
        assertThat(results.get(0).chunk().id()).isEqualTo("c2");
        assertThat(results.get(1).chunk().id()).isEqualTo("c1");
    }

    @Test
    void rerank_nullRejection() {
        FakeLlmClient fake = new FakeLlmClient();
        LlmJudgeReranker reranker = new LlmJudgeReranker(fake, "model", SYNC_EXECUTOR);
        Chunk c1 = chunk("c1", "text");

        assertThatNullPointerException()
            .isThrownBy(() -> reranker.rerank(null, List.of(c1), 5));
        assertThatNullPointerException()
            .isThrownBy(() -> reranker.rerank("query", null, 5));
    }

    // ---- helpers ----

    private Chunk chunk(String id, String text) {
        return new Chunk(id, "doc-" + id, text, 0, text.length() / 4, null, Map.of());
    }

    private ChatResponse scoreResponse(String score) {
        return new ChatResponse(
            "r1", "model", "fake",
            Message.assistant(score),
            new TokenCount(10, 1, "fake"),
            Duration.ZERO, "stop", null, null, Map.of()
        );
    }

    static final class FakeLlmClient implements LlmClient {
        private final Queue<ChatResponse> responses = new ConcurrentLinkedQueue<>();
        int callCount = 0;

        void enqueue(ChatResponse response) {
            responses.add(response);
        }

        @Override
        public Flow.Publisher<StreamEvent> stream(ChatRequest request, CancellationToken cancellationToken) {
            throw new UnsupportedOperationException();
        }

        @Override
        public ChatResponse complete(ChatRequest request, CancellationToken cancellationToken) {
            callCount++;
            ChatResponse r = responses.poll();
            if (r == null) {
                throw new IllegalStateException("No fake response enqueued");
            }
            return r;
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
