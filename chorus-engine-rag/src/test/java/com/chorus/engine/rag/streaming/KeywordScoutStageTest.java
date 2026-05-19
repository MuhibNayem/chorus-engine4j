package com.chorus.engine.rag.streaming;

import com.chorus.engine.rag.document.Chunk;
import com.chorus.engine.rag.retrieval.HybridRetrievalEngine;
import com.chorus.engine.rag.retrieval.RetrievalEngine;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutionException;

import static org.assertj.core.api.Assertions.*;

class KeywordScoutStageTest {

    @Test
    void retrieve_returnsChunksFromKeywordIndex() throws ExecutionException, InterruptedException {
        FakeKeywordIndex index = new FakeKeywordIndex();
        Chunk c1 = new Chunk("c1", "d1", "text", 0, 1, null, Map.of());
        index.addResult(c1, 0.9);

        KeywordScoutStage stage = new KeywordScoutStage(index, 5);

        List<Chunk> results = stage.retrieve("query", RetrievalEngine.RetrieveOptions.defaults(5)).get();

        assertThat(results).hasSize(1);
        assertThat(results.get(0).id()).isEqualTo("c1");
    }

    @Test
    void retrieve_withEmptyResults() throws ExecutionException, InterruptedException {
        FakeKeywordIndex index = new FakeKeywordIndex();
        KeywordScoutStage stage = new KeywordScoutStage(index, 5);

        List<Chunk> results = stage.retrieve("query", RetrievalEngine.RetrieveOptions.defaults(5)).get();

        assertThat(results).isEmpty();
    }

    @Test
    void retrieve_rejectsNullQuery() {
        FakeKeywordIndex index = new FakeKeywordIndex();
        KeywordScoutStage stage = new KeywordScoutStage(index, 5);

        assertThatNullPointerException()
            .isThrownBy(() -> stage.retrieve(null, RetrievalEngine.RetrieveOptions.defaults(5)));
    }

    @Test
    void retrieve_rejectsNullOptions() {
        FakeKeywordIndex index = new FakeKeywordIndex();
        KeywordScoutStage stage = new KeywordScoutStage(index, 5);

        assertThatNullPointerException()
            .isThrownBy(() -> stage.retrieve("query", null));
    }

    @Test
    void stageMetadata_isCorrect() {
        FakeKeywordIndex index = new FakeKeywordIndex();
        KeywordScoutStage stage = new KeywordScoutStage(index, 5);

        assertThat(stage.name()).isEqualTo("scout-keyword");
        assertThat(stage.priority()).isEqualTo(1);
        assertThat(stage.estimatedLatencyMs()).isEqualTo(15);
    }

    // ---- helpers ----

    static final class FakeKeywordIndex implements HybridRetrievalEngine.KeywordIndex {
        private final List<RetrievalResult> results = new ArrayList<>();

        void addResult(Chunk chunk, double score) {
            results.add(new RetrievalResult(chunk, score));
        }

        @Override
        public List<RetrievalResult> search(String query, int topK, Map<String, Object> filters) {
            return results.stream().limit(topK).toList();
        }

        @Override
        public void index(List<Chunk> chunks) {
        }

        @Override
        public void remove(Set<String> chunkIds) {
        }
    }
}
