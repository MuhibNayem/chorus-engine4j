package com.chorus.engine.memory;

import com.chorus.engine.core.context.Message;
import com.chorus.engine.memory.fakes.FakeEmbeddingClient;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.*;

class LongTermMemoryTest {

    @Test
    void storeAndRetrieve() {
        FakeEmbeddingClient client = new FakeEmbeddingClient(8);
        LongTermMemory memory = new LongTermMemory(client, "fake-model");

        Message msg = Message.user("hello world");
        memory.store(msg, "key-1", Map.of("tag", "test"));

        List<LongTermMemory.RetrievalResult> results = memory.retrieve("hello", 5);

        assertThat(results).isNotEmpty();
        assertThat(results.get(0).key()).isEqualTo("key-1");
    }

    @Test
    void bm25PlusSemanticHybridScoring() {
        FakeEmbeddingClient client = new FakeEmbeddingClient(8);
        LongTermMemory memory = new LongTermMemory(client, "fake-model");

        // Store two documents with different embeddings
        client.setEmbedding("alpha beta gamma", new float[]{1.0f, 0.0f, 0.0f, 0.0f, 0.0f, 0.0f, 0.0f, 0.0f});
        client.setEmbedding("delta epsilon zeta", new float[]{0.0f, 1.0f, 0.0f, 0.0f, 0.0f, 0.0f, 0.0f, 0.0f});
        client.setEmbedding("alpha query", new float[]{1.0f, 0.0f, 0.0f, 0.0f, 0.0f, 0.0f, 0.0f, 0.0f});

        memory.store(Message.user("alpha beta gamma"), "doc-1", Map.of());
        memory.store(Message.user("delta epsilon zeta"), "doc-2", Map.of());

        List<LongTermMemory.RetrievalResult> results = memory.retrieve("alpha query", 5);

        assertThat(results).hasSize(2);
        // doc-1 should rank higher due to both BM25 (term overlap on "alpha") and semantic similarity
        assertThat(results.get(0).key()).isEqualTo("doc-1");
        assertThat(results.get(0).score()).isGreaterThan(results.get(1).score());
    }

    @Test
    void emptyStoreRetrieveReturnsEmpty() {
        LongTermMemory memory = new LongTermMemory(null, null);
        List<LongTermMemory.RetrievalResult> results = memory.retrieve("anything", 5);
        assertThat(results).isEmpty();
    }

    @Test
    void deleteRemovesDocument() {
        FakeEmbeddingClient client = new FakeEmbeddingClient(8);
        LongTermMemory memory = new LongTermMemory(client, "fake-model");

        memory.store(Message.user("to delete"), "del-key", Map.of());
        assertThat(memory.size()).isEqualTo(1);

        boolean removed = memory.delete("del-key");
        assertThat(removed).isTrue();
        assertThat(memory.size()).isEqualTo(0);

        List<LongTermMemory.RetrievalResult> results = memory.retrieve("to delete", 5);
        assertThat(results).isEmpty();
    }

    @Test
    void deleteNonExistentReturnsFalse() {
        LongTermMemory memory = new LongTermMemory(null, null);
        assertThat(memory.delete("missing")).isFalse();
    }

    @Test
    void retrieveRespectsTopK() {
        FakeEmbeddingClient client = new FakeEmbeddingClient(8);
        LongTermMemory memory = new LongTermMemory(client, "fake-model");

        for (int i = 0; i < 10; i++) {
            memory.store(Message.user("doc " + i), "key-" + i, Map.of());
        }

        List<LongTermMemory.RetrievalResult> results = memory.retrieve("doc", 3);
        assertThat(results).hasSizeLessThanOrEqualTo(3);
    }

    @Test
    void nullMessageRejection() {
        FakeEmbeddingClient client = new FakeEmbeddingClient(8);
        LongTermMemory memory = new LongTermMemory(client, "fake-model");
        assertThatThrownBy(() -> memory.store(null, "key", Map.of()))
            .isInstanceOf(NullPointerException.class);
    }

    @Test
    void nullKeyRejection() {
        FakeEmbeddingClient client = new FakeEmbeddingClient(8);
        LongTermMemory memory = new LongTermMemory(client, "fake-model");
        assertThatThrownBy(() -> memory.store(Message.user("test"), null, Map.of()))
            .isInstanceOf(NullPointerException.class);
    }

    @Test
    void nullQueryRejection() {
        LongTermMemory memory = new LongTermMemory(null, null);
        assertThatThrownBy(() -> memory.retrieve(null, 5))
            .isInstanceOf(NullPointerException.class);
    }
}
