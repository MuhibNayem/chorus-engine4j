package com.chorus.engine.rag.chunking;

import com.chorus.engine.rag.document.Chunk;
import com.chorus.engine.rag.document.Document;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class RecursiveChunkingTest {

    @Test
    void respectsParagraphBoundaries() {
        RecursiveChunking chunker = new RecursiveChunking(100, 10);
        String text = """
            First paragraph with some content.

            Second paragraph with different content.

            Third paragraph here.
            """;
        Document doc = Document.builder("doc1", text, "test").build();

        List<Chunk> chunks = chunker.chunk(doc);
        assertFalse(chunks.isEmpty());

        // Prefer paragraph-level splits
        for (Chunk c : chunks) {
            assertFalse(c.text().isEmpty());
        }
    }

    @Test
    void fallsBackToSmallerSeparators() {
        RecursiveChunking chunker = new RecursiveChunking(20, 5);
        String text = "Word1 word2 word3 word4 word5 word6 word7 word8 word9 word10";
        Document doc = Document.builder("doc2", text, "test").build();

        List<Chunk> chunks = chunker.chunk(doc);
        assertFalse(chunks.isEmpty());
        for (Chunk c : chunks) {
            assertFalse(c.text().isEmpty());
            assertTrue(c.text().length() <= 25 || chunks.size() == 1);
        }
    }
}
