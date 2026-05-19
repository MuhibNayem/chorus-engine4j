package com.chorus.engine.rag.retrieval;

import com.chorus.engine.rag.document.Chunk;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class HybridRetrievalEngineTest {

    @Test
    void retrievalResultHoldsData() {
        Chunk chunk = new Chunk("c1", "doc1", "hello", 0, 1, null, Map.of());
        RetrievalEngine.RetrievalResult result = new RetrievalEngine.RetrievalResult(chunk, 0.95, "dense");

        assertEquals("c1", result.chunk().id());
        assertEquals(0.95, result.score(), 0.001);
        assertEquals("dense", result.sourceEngine());
    }

    @Test
    void retrieveOptionsDefaults() {
        RetrievalEngine.RetrieveOptions opts = RetrievalEngine.RetrieveOptions.defaults(5);
        assertEquals(5, opts.topK());
        assertTrue(opts.filters().isEmpty());
        assertTrue(opts.includeMetadata());
    }
}
