package com.chorus.engine.rag.chunking;

import com.chorus.engine.rag.document.Chunk;
import com.chorus.engine.rag.document.Document;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class FixedSizeChunkingTest {

    @Test
    void chunksDocumentWithOverlap() {
        FixedSizeChunking chunker = new FixedSizeChunking(50, 10);
        Document doc = Document.builder("doc1", "The quick brown fox jumps over the lazy dog. " +
            "Pack my box with five dozen liquor jugs. " +
            "How vexingly quick daft zebras jump!", "test").build();

        List<Chunk> chunks = chunker.chunk(doc);
        assertFalse(chunks.isEmpty());
        assertTrue(chunks.size() >= 2);

        // All chunks belong to the document
        for (Chunk c : chunks) {
            assertEquals("doc1", c.documentId());
            assertFalse(c.text().isEmpty());
            assertTrue(c.text().length() <= 55); // word boundary may extend slightly
        }

        // Indices are sequential
        for (int i = 0; i < chunks.size(); i++) {
            assertEquals(i, chunks.get(i).index());
        }
    }

    @Test
    void shortDocumentProducesSingleChunk() {
        FixedSizeChunking chunker = new FixedSizeChunking(500, 50);
        Document doc = Document.builder("doc2", "Short text.", "test").build();

        List<Chunk> chunks = chunker.chunk(doc);
        assertEquals(1, chunks.size());
        assertEquals("Short text.", chunks.get(0).text());
    }

    @Test
    void overlapIsPreserved() {
        FixedSizeChunking chunker = new FixedSizeChunking(20, 5);
        String text = "ABCDEFGHIJKLMNOPQRSTUVWXYZ";
        Document doc = Document.builder("doc3", text, "test").build();

        List<Chunk> chunks = chunker.chunk(doc);
        assertTrue(chunks.size() >= 2);

        // Some overlap between consecutive chunks
        if (chunks.size() >= 2) {
            String first = chunks.get(0).text();
            String second = chunks.get(1).text();
            boolean hasOverlap = false;
            for (int i = 1; i <= 5 && i < first.length(); i++) {
                String suffix = first.substring(first.length() - i);
                if (second.contains(suffix)) {
                    hasOverlap = true;
                    break;
                }
            }
            assertTrue(hasOverlap, "Expected overlap between chunks");
        }
    }

    @Test
    void invalidParametersThrow() {
        assertThrows(IllegalArgumentException.class, () -> new FixedSizeChunking(0, 0));
        assertThrows(IllegalArgumentException.class, () -> new FixedSizeChunking(100, 100));
        assertThrows(IllegalArgumentException.class, () -> new FixedSizeChunking(100, -1));
    }
}
