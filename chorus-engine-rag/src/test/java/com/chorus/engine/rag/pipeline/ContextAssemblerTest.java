package com.chorus.engine.rag.pipeline;

import com.chorus.engine.rag.document.Chunk;
import com.chorus.engine.rag.retrieval.RetrievalEngine;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class ContextAssemblerTest {

    @Test
    void assemblesContextWithinBudget() {
        ContextAssembler assembler = new ContextAssembler(100, 4);

        List<RetrievalEngine.RetrievalResult> results = List.of(
            result("c1", "First chunk of text here."),
            result("c2", "Second chunk of text here."),
            result("c3", "Third chunk of text here.")
        );

        ContextAssembler.AssembledContext assembled = assembler.assemble("query", results, null, 0);

        assertFalse(assembled.contextText().isEmpty());
        assertTrue(assembled.usedTokens() <= 100);
        assertEquals(3, assembled.citations().size());

        // Citations are numbered sequentially
        assertEquals(1, assembled.citations().get(0).id());
        assertEquals(2, assembled.citations().get(1).id());
        assertEquals(3, assembled.citations().get(2).id());
    }

    @Test
    void deduplicatesByChunkId() {
        ContextAssembler assembler = new ContextAssembler(500, 4);
        Chunk chunk = new Chunk("c1", "doc1", "duplicate text", 0, 2, null, Map.of());

        List<RetrievalEngine.RetrievalResult> results = List.of(
            new RetrievalEngine.RetrievalResult(chunk, 0.9, "dense"),
            new RetrievalEngine.RetrievalResult(chunk, 0.7, "sparse")
        );

        ContextAssembler.AssembledContext assembled = assembler.assemble("query", results, null, 0);
        assertEquals(1, assembled.citations().size());
    }

    @Test
    void respectsTokenBudget() {
        ContextAssembler assembler = new ContextAssembler(10, 4);

        List<RetrievalEngine.RetrievalResult> results = List.of(
            result("c1", "This is a very long chunk of text that should exceed the budget."),
            result("c2", "Another chunk."),
            result("c3", "Third chunk.")
        );

        ContextAssembler.AssembledContext assembled = assembler.assemble("query", results, null, 0);
        // Should only include first chunk since it roughly uses ~60/4 = 15 tokens > 10
        // Actually first chunk is ~60 chars = ~15 tokens, exceeds 10, so nothing included
        assertTrue(assembled.usedTokens() <= 10);
    }

    private RetrievalEngine.RetrievalResult result(String id, String text) {
        return new RetrievalEngine.RetrievalResult(
            new Chunk(id, "doc1", text, 0, 2, null, Map.of()),
            0.5, "dense"
        );
    }
}
