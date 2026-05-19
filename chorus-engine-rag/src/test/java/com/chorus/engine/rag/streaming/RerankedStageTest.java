package com.chorus.engine.rag.streaming;

import com.chorus.engine.rag.document.Chunk;
import com.chorus.engine.rag.rerank.Reranker;
import com.chorus.engine.rag.retrieval.RetrievalEngine;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;

import static org.assertj.core.api.Assertions.*;

class RerankedStageTest {

    @Test
    void retrieve_throwsUnsupportedOperationException() {
        FakeReranker fakeReranker = new FakeReranker();
        RerankedStage stage = new RerankedStage(fakeReranker, 5);

        assertThatThrownBy(() -> stage.retrieve("query", RetrievalEngine.RetrieveOptions.defaults(5)))
            .isInstanceOf(UnsupportedOperationException.class)
            .hasMessageContaining("orchestrator");
    }

    @Test
    void rerank_returnsOrderedChunks() throws ExecutionException, InterruptedException {
        FakeReranker fakeReranker = new FakeReranker();
        Chunk c1 = new Chunk("c1", "d1", "text1", 0, 1, null, Map.of());
        Chunk c2 = new Chunk("c2", "d2", "text2", 0, 1, null, Map.of());
        fakeReranker.addResult(c2, 0.9);
        fakeReranker.addResult(c1, 0.5);

        RerankedStage stage = new RerankedStage(fakeReranker, 5);

        List<Chunk> results = stage.rerank("query", List.of(c1, c2)).get();

        assertThat(results).hasSize(2);
        assertThat(results.get(0).id()).isEqualTo("c2");
        assertThat(results.get(1).id()).isEqualTo("c1");
    }

    @Test
    void rerank_withEmptyCandidates() throws ExecutionException, InterruptedException {
        FakeReranker fakeReranker = new FakeReranker();
        RerankedStage stage = new RerankedStage(fakeReranker, 5);

        List<Chunk> results = stage.rerank("query", List.of()).get();

        assertThat(results).isEmpty();
    }

    @Test
    void rerank_limitsTopN() throws ExecutionException, InterruptedException {
        FakeReranker fakeReranker = new FakeReranker();
        Chunk c1 = new Chunk("c1", "d1", "text1", 0, 1, null, Map.of());
        Chunk c2 = new Chunk("c2", "d2", "text2", 0, 1, null, Map.of());
        Chunk c3 = new Chunk("c3", "d3", "text3", 0, 1, null, Map.of());
        fakeReranker.addResult(c3, 0.9);
        fakeReranker.addResult(c2, 0.7);
        fakeReranker.addResult(c1, 0.5);

        RerankedStage stage = new RerankedStage(fakeReranker, 2);

        List<Chunk> results = stage.rerank("query", List.of(c1, c2, c3)).get();

        assertThat(results).hasSize(2);
        assertThat(results.get(0).id()).isEqualTo("c3");
        assertThat(results.get(1).id()).isEqualTo("c2");
    }

    @Test
    void rerank_rejectsNullQuery() {
        FakeReranker fakeReranker = new FakeReranker();
        RerankedStage stage = new RerankedStage(fakeReranker, 5);

        assertThatNullPointerException()
            .isThrownBy(() -> stage.rerank(null, List.of()));
    }

    @Test
    void rerank_rejectsNullCandidates() {
        FakeReranker fakeReranker = new FakeReranker();
        RerankedStage stage = new RerankedStage(fakeReranker, 5);

        assertThatNullPointerException()
            .isThrownBy(() -> stage.rerank("query", null));
    }

    @Test
    void stageMetadata_isCorrect() {
        FakeReranker fakeReranker = new FakeReranker();
        RerankedStage stage = new RerankedStage(fakeReranker, 5);

        assertThat(stage.name()).isEqualTo("reranked");
        assertThat(stage.priority()).isEqualTo(3);
        assertThat(stage.estimatedLatencyMs()).isEqualTo(500);
    }

    // ---- helpers ----

    static final class FakeReranker implements Reranker {
        private final List<RankedResult> results = new ArrayList<>();

        void addResult(Chunk chunk, double score) {
            results.add(new RankedResult(chunk, score, "fake"));
        }

        @Override
        public List<RankedResult> rerank(String query, List<Chunk> candidates, int topN) {
            return results.stream()
                .filter(r -> candidates.contains(r.chunk()))
                .sorted((a, b) -> Double.compare(b.relevanceScore(), a.relevanceScore()))
                .limit(topN)
                .toList();
        }
    }
}
