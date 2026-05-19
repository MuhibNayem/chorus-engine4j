package com.chorus.engine.rag.streaming;

import com.chorus.engine.core.result.Result;
import com.chorus.engine.llm.embed.EmbeddingClient;
import com.chorus.engine.rag.document.Chunk;
import com.chorus.engine.rag.retrieval.RetrievalEngine;
import com.chorus.engine.rag.store.VectorStore;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;

import static org.assertj.core.api.Assertions.*;

class DenseVectorStageTest {

    @Test
    void retrieve_returnsChunksFromVectorStore() throws ExecutionException, InterruptedException {
        FakeEmbeddingClient embedClient = new FakeEmbeddingClient(new float[]{1.0f, 0.0f, 0.0f});
        FakeVectorStore vectorStore = new FakeVectorStore();
        Chunk c1 = new Chunk("c1", "d1", "text", 0, 1, null, Map.of());
        vectorStore.addResult(c1, 0.95);

        DenseVectorStage stage = new DenseVectorStage(vectorStore, embedClient, 5);

        List<Chunk> results = stage.retrieve("query", RetrievalEngine.RetrieveOptions.defaults(5)).get();

        assertThat(results).hasSize(1);
        assertThat(results.get(0).id()).isEqualTo("c1");
    }

    @Test
    void retrieve_withEmptyResults() throws ExecutionException, InterruptedException {
        FakeEmbeddingClient embedClient = new FakeEmbeddingClient(new float[]{1.0f, 0.0f, 0.0f});
        FakeVectorStore vectorStore = new FakeVectorStore();

        DenseVectorStage stage = new DenseVectorStage(vectorStore, embedClient, 5);

        List<Chunk> results = stage.retrieve("query", RetrievalEngine.RetrieveOptions.defaults(5)).get();

        assertThat(results).isEmpty();
    }

    @Test
    void retrieve_propagatesFilters() throws ExecutionException, InterruptedException {
        FakeEmbeddingClient embedClient = new FakeEmbeddingClient(new float[]{1.0f, 0.0f, 0.0f});
        FakeVectorStore vectorStore = new FakeVectorStore();
        Chunk c1 = new Chunk("c1", "d1", "text", 0, 1, null, Map.of("lang", "en"));
        vectorStore.addResult(c1, 0.95);

        DenseVectorStage stage = new DenseVectorStage(vectorStore, embedClient, 5);

        var options = new RetrievalEngine.RetrieveOptions(5, Map.of("lang", "en"), true);
        List<Chunk> results = stage.retrieve("query", options).get();

        assertThat(results).hasSize(1);
        assertThat(vectorStore.lastFilters).containsEntry("lang", "en");
    }

    @Test
    void retrieve_rejectsNullQuery() {
        FakeEmbeddingClient embedClient = new FakeEmbeddingClient(new float[]{1.0f, 0.0f, 0.0f});
        FakeVectorStore vectorStore = new FakeVectorStore();
        DenseVectorStage stage = new DenseVectorStage(vectorStore, embedClient, 5);

        assertThatNullPointerException()
            .isThrownBy(() -> stage.retrieve(null, RetrievalEngine.RetrieveOptions.defaults(5)));
    }

    @Test
    void retrieve_rejectsNullOptions() {
        FakeEmbeddingClient embedClient = new FakeEmbeddingClient(new float[]{1.0f, 0.0f, 0.0f});
        FakeVectorStore vectorStore = new FakeVectorStore();
        DenseVectorStage stage = new DenseVectorStage(vectorStore, embedClient, 5);

        assertThatNullPointerException()
            .isThrownBy(() -> stage.retrieve("query", null));
    }

    @Test
    void stageMetadata_isCorrect() {
        FakeEmbeddingClient embedClient = new FakeEmbeddingClient(new float[]{1.0f, 0.0f, 0.0f});
        FakeVectorStore vectorStore = new FakeVectorStore();
        DenseVectorStage stage = new DenseVectorStage(vectorStore, embedClient, 5);

        assertThat(stage.name()).isEqualTo("dense-vector");
        assertThat(stage.priority()).isEqualTo(2);
        assertThat(stage.estimatedLatencyMs()).isEqualTo(150);
    }

    // ---- helpers ----

    static final class FakeEmbeddingClient implements EmbeddingClient {
        private final float[] embedding;

        FakeEmbeddingClient(float[] embedding) {
            this.embedding = embedding;
        }

        @Override
        public Result<float[], EmbeddingError> embed(String text, EmbedOptions options) {
            return Result.ok(embedding);
        }

        @Override
        public Result<List<float[]>, EmbeddingError> embedBatch(List<String> texts, EmbedOptions options) {
            return Result.ok(texts.stream().map(t -> embedding).toList());
        }

        @Override
        public String providerName() {
            return "fake";
        }

        @Override
        public String modelName() {
            return "fake-model";
        }

        @Override
        public int nativeDimensions() {
            return embedding.length;
        }

        @Override
        public boolean isLocal() {
            return true;
        }

        @Override
        public HealthStatus health() {
            return HealthStatus.HEALTHY;
        }
    }

    static final class FakeVectorStore implements VectorStore {
        private final List<RetrievalResult> results = new ArrayList<>();
        Map<String, Object> lastFilters;

        void addResult(Chunk chunk, double score) {
            results.add(new RetrievalResult(chunk, score));
        }

        @Override
        public void upsert(List<Chunk> chunks) {
        }

        @Override
        public List<RetrievalResult> search(float[] queryEmbedding, int topK, Map<String, Object> filters) {
            this.lastFilters = filters;
            return results.stream().limit(topK).toList();
        }

        @Override
        public void delete(java.util.Set<String> chunkIds) {
        }

        @Override
        public void deleteByDocument(String documentId) {
        }

        @Override
        public long count() {
            return results.size();
        }

        @Override
        public String storeName() {
            return "fake";
        }
    }
}
