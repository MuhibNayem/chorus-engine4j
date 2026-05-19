package com.chorus.engine.rag.store;

import com.chorus.engine.core.vector.VectorOperations;
import com.chorus.engine.rag.document.Chunk;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class InMemoryVectorStoreTest {

    private InMemoryVectorStore store;

    @BeforeEach
    void setUp() {
        store = new InMemoryVectorStore(VectorOperations.autoDetect());
    }

    @Test
    void upsertAndSearch() {
        float[] emb1 = {1.0f, 0.0f, 0.0f};
        float[] emb2 = {0.0f, 1.0f, 0.0f};
        float[] emb3 = {0.5f, 0.5f, 0.0f};

        Chunk c1 = new Chunk("c1", "d1", "hello world", 0, 2, null, Map.of()).withEmbedding(emb1);
        Chunk c2 = new Chunk("c2", "d1", "foo bar", 1, 2, null, Map.of()).withEmbedding(emb2);
        Chunk c3 = new Chunk("c3", "d2", "baz qux", 0, 2, null, Map.of()).withEmbedding(emb3);

        store.upsert(List.of(c1, c2, c3));
        assertEquals(3, store.count());

        // Search with query close to emb1
        float[] query = {0.9f, 0.1f, 0.0f};
        List<VectorStore.RetrievalResult> results = store.search(query, 2, Map.of());

        assertEquals(2, results.size());
        assertEquals("c1", results.get(0).chunk().id());
        assertTrue(results.get(0).score() > results.get(1).score());
    }

    @Test
    void searchWithFilters() {
        float[] emb = {1.0f, 0.0f, 0.0f};
        Chunk c1 = new Chunk("c1", "d1", "text1", 0, 1, null, Map.of("lang", "en")).withEmbedding(emb);
        Chunk c2 = new Chunk("c2", "d1", "text2", 1, 1, null, Map.of("lang", "fr")).withEmbedding(emb);

        store.upsert(List.of(c1, c2));

        List<VectorStore.RetrievalResult> results = store.search(emb, 10, Map.of("lang", "en"));
        assertEquals(1, results.size());
        assertEquals("c1", results.get(0).chunk().id());
    }

    @Test
    void deleteByChunkId() {
        Chunk c1 = new Chunk("c1", "d1", "text", 0, 1, null, Map.of()).withEmbedding(new float[]{1, 0, 0});
        store.upsert(List.of(c1));
        assertEquals(1, store.count());

        store.delete(Set.of("c1"));
        assertEquals(0, store.count());
    }

    @Test
    void deleteByDocumentId() {
        Chunk c1 = new Chunk("c1", "d1", "text1", 0, 1, null, Map.of()).withEmbedding(new float[]{1, 0, 0});
        Chunk c2 = new Chunk("c2", "d2", "text2", 0, 1, null, Map.of()).withEmbedding(new float[]{0, 1, 0});
        store.upsert(List.of(c1, c2));
        assertEquals(2, store.count());

        store.deleteByDocument("d1");
        assertEquals(1, store.count());
        assertEquals("d2", store.search(new float[]{0, 1, 0}, 10, Map.of()).get(0).chunk().documentId());
    }

    @Test
    void storeNameIsInMemory() {
        assertEquals("in_memory", store.storeName());
    }
}
