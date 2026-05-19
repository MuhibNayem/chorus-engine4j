package com.chorus.engine.rag.retrieval;

import com.chorus.engine.core.result.Result;
import com.chorus.engine.llm.embed.EmbeddingClient;
import com.chorus.engine.rag.document.Chunk;
import com.chorus.engine.rag.store.VectorStore;
import org.junit.jupiter.api.Test;

import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

class HybridRetrievalEngineTest {

    @Test
    void retrieveWithDenseAndSparseFusion() {
        FakeVectorStore vectorStore = new FakeVectorStore();
        FakeEmbeddingClient embedClient = new FakeEmbeddingClient();
        FakeKeywordIndex keywordIndex = new FakeKeywordIndex();

        Chunk c1 = chunk("c1", "Paris is the capital of France");
        Chunk c2 = chunk("c2", "Berlin is the capital of Germany");
        Chunk c3 = chunk("c3", "Madrid is the capital of Spain");

        vectorStore.addResult(c1, 0.95);
        vectorStore.addResult(c2, 0.90);
        keywordIndex.addResult(c2, 0.85);
        keywordIndex.addResult(c3, 0.80);

        HybridRetrievalEngine engine = new HybridRetrievalEngine(vectorStore, embedClient, keywordIndex, 10, 10, 60.0);
        List<RetrievalEngine.RetrievalResult> results = engine.retrieve("capital", RetrievalEngine.RetrieveOptions.defaults(5));

        assertThat(results).hasSize(3);
        // c2 appears in both dense and sparse, so should have highest RRF score
        assertThat(results.get(0).chunk().id()).isEqualTo("c2");
        assertThat(results.get(0).sourceEngine()).isEqualTo("hybrid");

        // c1 only dense, c3 only sparse
        List<String> remainingIds = results.subList(1, 3).stream().map(r -> r.chunk().id()).toList();
        assertThat(remainingIds).containsExactlyInAnyOrder("c1", "c3");
    }

    @Test
    void rrfMathIsCorrect() {
        FakeVectorStore vectorStore = new FakeVectorStore();
        FakeEmbeddingClient embedClient = new FakeEmbeddingClient();
        FakeKeywordIndex keywordIndex = new FakeKeywordIndex();

        Chunk c1 = chunk("c1", "text1");
        Chunk c2 = chunk("c2", "text2");

        // c1 ranks 1st in dense (score irrelevant for ranking, order matters)
        // c2 ranks 1st in sparse
        vectorStore.addResult(c1, 0.9);
        vectorStore.addResult(c2, 0.8);
        keywordIndex.addResult(c2, 0.9);
        keywordIndex.addResult(c1, 0.7);

        double rrfK = 60.0;
        HybridRetrievalEngine engine = new HybridRetrievalEngine(vectorStore, embedClient, keywordIndex, 10, 10, rrfK);
        List<RetrievalEngine.RetrievalResult> results = engine.retrieve("q", RetrievalEngine.RetrieveOptions.defaults(5));

        // c1: dense rank 1 -> 1/(60+1) = 1/61; sparse rank 2 -> 1/(60+2) = 1/62
        double c1Expected = 1.0 / (rrfK + 1) + 1.0 / (rrfK + 2);
        // c2: dense rank 2 -> 1/62; sparse rank 1 -> 1/61
        double c2Expected = 1.0 / (rrfK + 2) + 1.0 / (rrfK + 1);

        assertThat(results.get(0).score()).isCloseTo(c1Expected, within(0.0001));
        assertThat(results.get(1).score()).isCloseTo(c2Expected, within(0.0001));
    }

    @Test
    void whenDenseReturnsEmpty() {
        FakeVectorStore vectorStore = new FakeVectorStore();
        FakeEmbeddingClient embedClient = new FakeEmbeddingClient();
        FakeKeywordIndex keywordIndex = new FakeKeywordIndex();

        Chunk c1 = chunk("c1", "text");
        keywordIndex.addResult(c1, 0.90);

        HybridRetrievalEngine engine = new HybridRetrievalEngine(vectorStore, embedClient, keywordIndex, 10, 10, 60.0);
        List<RetrievalEngine.RetrievalResult> results = engine.retrieve("q", RetrievalEngine.RetrieveOptions.defaults(5));

        assertThat(results).hasSize(1);
        assertThat(results.get(0).chunk().id()).isEqualTo("c1");
        assertThat(results.get(0).sourceEngine()).isEqualTo("sparse");
    }

    @Test
    void whenSparseReturnsEmpty() {
        FakeVectorStore vectorStore = new FakeVectorStore();
        FakeEmbeddingClient embedClient = new FakeEmbeddingClient();
        FakeKeywordIndex keywordIndex = new FakeKeywordIndex();

        Chunk c1 = chunk("c1", "text");
        vectorStore.addResult(c1, 0.90);

        HybridRetrievalEngine engine = new HybridRetrievalEngine(vectorStore, embedClient, keywordIndex, 10, 10, 60.0);
        List<RetrievalEngine.RetrievalResult> results = engine.retrieve("q", RetrievalEngine.RetrieveOptions.defaults(5));

        assertThat(results).hasSize(1);
        assertThat(results.get(0).chunk().id()).isEqualTo("c1");
        assertThat(results.get(0).sourceEngine()).isEqualTo("dense");
    }

    @Test
    void whenBothReturnEmpty() {
        FakeVectorStore vectorStore = new FakeVectorStore();
        FakeEmbeddingClient embedClient = new FakeEmbeddingClient();
        FakeKeywordIndex keywordIndex = new FakeKeywordIndex();

        HybridRetrievalEngine engine = new HybridRetrievalEngine(vectorStore, embedClient, keywordIndex, 10, 10, 60.0);
        List<RetrievalEngine.RetrievalResult> results = engine.retrieve("q", RetrievalEngine.RetrieveOptions.defaults(5));

        assertThat(results).isEmpty();
    }

    @Test
    void filterPropagation() {
        FakeVectorStore vectorStore = new FakeVectorStore();
        FakeEmbeddingClient embedClient = new FakeEmbeddingClient();
        FakeKeywordIndex keywordIndex = new FakeKeywordIndex();

        Chunk c1 = chunk("c1", "text", Map.of("lang", "en"));
        Chunk c2 = chunk("c2", "text", Map.of("lang", "fr"));

        vectorStore.addResult(c1, 0.90);
        vectorStore.addResult(c2, 0.85);
        keywordIndex.addResult(c1, 0.80);
        keywordIndex.addResult(c2, 0.75);

        HybridRetrievalEngine engine = new HybridRetrievalEngine(vectorStore, embedClient, keywordIndex, 10, 10, 60.0);
        List<RetrievalEngine.RetrievalResult> results = engine.retrieve(
            "q",
            new RetrievalEngine.RetrieveOptions(10, Map.of("lang", "en"), true)
        );

        assertThat(results).allMatch(r -> r.chunk().metadata().get("lang").equals("en"));
    }

    @Test
    void topKLimit() {
        FakeVectorStore vectorStore = new FakeVectorStore();
        FakeEmbeddingClient embedClient = new FakeEmbeddingClient();
        FakeKeywordIndex keywordIndex = new FakeKeywordIndex();

        for (int i = 0; i < 10; i++) {
            Chunk c = chunk("c" + i, "text " + i);
            vectorStore.addResult(c, 0.9 - i * 0.01);
        }

        HybridRetrievalEngine engine = new HybridRetrievalEngine(vectorStore, embedClient, keywordIndex, 10, 10, 60.0);
        List<RetrievalEngine.RetrievalResult> results = engine.retrieve("q", RetrievalEngine.RetrieveOptions.defaults(3));

        assertThat(results).hasSize(3);
    }

    @Test
    void nullRejection() {
        FakeVectorStore vectorStore = new FakeVectorStore();
        FakeEmbeddingClient embedClient = new FakeEmbeddingClient();
        FakeKeywordIndex keywordIndex = new FakeKeywordIndex();

        HybridRetrievalEngine engine = new HybridRetrievalEngine(vectorStore, embedClient, keywordIndex, 10, 10, 60.0);

        assertThatThrownBy(() -> engine.retrieve(null, RetrievalEngine.RetrieveOptions.defaults(5)))
            .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> engine.retrieve("q", null))
            .isInstanceOf(NullPointerException.class);
    }

    // ---- fakes ----

    static class FakeVectorStore implements VectorStore {
        private final List<VectorStore.RetrievalResult> results = new ArrayList<>();

        void addResult(Chunk chunk, double score) {
            results.add(new VectorStore.RetrievalResult(chunk, score));
        }

        @Override
        public void upsert(List<Chunk> chunks) {}

        @Override
        public List<VectorStore.RetrievalResult> search(float[] queryEmbedding, int topK, Map<String, Object> filters) {
            return results.stream()
                .filter(r -> matchesFilters(r.chunk(), filters))
                .limit(topK)
                .toList();
        }

        @Override public void delete(Set<String> chunkIds) {}
        @Override public void deleteByDocument(String documentId) {}
        @Override public long count() { return results.size(); }
        @Override public String storeName() { return "fake"; }

        private boolean matchesFilters(Chunk chunk, Map<String, Object> filters) {
            for (Map.Entry<String, Object> f : filters.entrySet()) {
                Object val = chunk.metadata().get(f.getKey());
                if (!Objects.equals(val, f.getValue())) return false;
            }
            return true;
        }
    }

    static class FakeEmbeddingClient implements EmbeddingClient {
        @Override
        public Result<float[], EmbeddingError> embed(String text, EmbedOptions options) {
            return Result.ok(new float[]{1.0f, 0.0f, 0.0f});
        }

        @Override
        public Result<List<float[]>, EmbeddingError> embedBatch(List<String> texts, EmbedOptions options) {
            return Result.ok(texts.stream().map(t -> new float[]{1.0f, 0.0f, 0.0f}).toList());
        }

        @Override public String providerName() { return "fake"; }
        @Override public String modelName() { return "fake-embed"; }
        @Override public int nativeDimensions() { return 3; }
        @Override public boolean isLocal() { return true; }
        @Override public HealthStatus health() { return HealthStatus.HEALTHY; }
    }

    static class FakeKeywordIndex implements HybridRetrievalEngine.KeywordIndex {
        private final List<RetrievalResult> results = new ArrayList<>();

        void addResult(Chunk chunk, double score) {
            results.add(new RetrievalResult(chunk, score));
        }

        @Override
        public List<RetrievalResult> search(String query, int topK, Map<String, Object> filters) {
            return results.stream()
                .filter(r -> matchesFilters(r.chunk(), filters))
                .limit(topK)
                .toList();
        }

        @Override public void index(List<Chunk> chunks) {}
        @Override public void remove(Set<String> chunkIds) {}

        private boolean matchesFilters(Chunk chunk, Map<String, Object> filters) {
            for (Map.Entry<String, Object> f : filters.entrySet()) {
                Object val = chunk.metadata().get(f.getKey());
                if (!Objects.equals(val, f.getValue())) return false;
            }
            return true;
        }
    }

    private Chunk chunk(String id, String text) {
        return new Chunk(id, "doc-1", text, 0, text.length(), null, Map.of());
    }

    private Chunk chunk(String id, String text, Map<String, Object> metadata) {
        return new Chunk(id, "doc-1", text, 0, text.length(), null, metadata);
    }
}
