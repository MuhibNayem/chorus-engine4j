package com.chorus.engine.core.memory;

import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.vectorstore.SimpleVectorStore;
import org.springframework.ai.vectorstore.VectorStore;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ChorusVectorStoreTest {

    @Test
    void testAddAndSearch() {
        TestEmbeddingModel embeddingModel = new TestEmbeddingModel();
        VectorStore springStore = SimpleVectorStore.builder(embeddingModel).build();

        ChorusVectorStore store = new ChorusVectorStore(springStore, embeddingModel);

        store.add("doc1", "Spring AI is a framework for AI development", Map.of("category", "framework"));
        store.add("doc2", "Java is a programming language", Map.of("category", "language"));
        store.add("doc3", "Vector stores enable semantic search", Map.of("category", "database"));

        List<SearchResult> results = store.search("AI framework", 2);

        assertThat(results).hasSizeGreaterThan(0);
    }

    @Test
    void testDelete() {
        TestEmbeddingModel embeddingModel = new TestEmbeddingModel();
        VectorStore springStore = SimpleVectorStore.builder(embeddingModel).build();

        ChorusVectorStore store = new ChorusVectorStore(springStore, embeddingModel);

        store.add("doc1", "Content one", Map.of());
        store.delete("doc1");

        List<SearchResult> results = store.search("Content");
        assertThat(results).isEmpty();
    }

    @Test
    void testEmbeddingService() {
        TestEmbeddingModel embeddingModel = new TestEmbeddingModel();
        EmbeddingService service = new EmbeddingService(embeddingModel);

        float[] embedding = service.embed("hello");
        assertThat(embedding).hasSize(3);

        List<Double> list = service.embedAsList("hello");
        assertThat(list).hasSize(3);
        assertThat(list.get(0)).isEqualTo(1.0);
    }

    @Test
    void testCreateDocument() {
        TestEmbeddingModel embeddingModel = new TestEmbeddingModel();
        EmbeddingService service = new EmbeddingService(embeddingModel);

        Document doc = service.createDocument("id1", "Hello world", Map.of("key", "value"));
        assertThat(doc.getId()).isEqualTo("id1");
        assertThat(doc.getText()).isEqualTo("Hello world");
        assertThat(doc.getMetadata()).containsEntry("key", "value");
    }

    static class TestEmbeddingModel implements EmbeddingModel {
        @Override
        public float[] embed(String text) {
            return new float[]{1.0f, 0.5f, 0.25f};
        }

        @Override
        public float[] embed(Document document) {
            return new float[]{1.0f, 0.5f, 0.25f};
        }

        @Override
        public org.springframework.ai.embedding.EmbeddingResponse call(org.springframework.ai.embedding.EmbeddingRequest request) {
            throw new UnsupportedOperationException();
        }
    }
}
