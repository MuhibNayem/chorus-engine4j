package com.chorus.engine.rag.streaming;

import com.chorus.engine.rag.document.Chunk;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class ContextAccumulatorTest {

    private Chunk chunk(String id, String text) {
        return new Chunk(id, "doc-" + id, text, 0, text.length() / 4, null, Map.of());
    }

    @Test
    void addsChunksFromSingleWave() {
        ContextAccumulator acc = new ContextAccumulator(1000);
        List<Chunk> chunks = List.of(
            chunk("c1", "First chunk of content"),
            chunk("c2", "Second chunk of content")
        );

        int added = acc.addWave(chunks, "scout", c -> 0.8);

        assertEquals(2, added);
        assertEquals(2, acc.chunkCount());
        assertTrue(acc.usedTokens() > 0);
        String ctx = acc.buildContext();
        assertTrue(ctx.contains("[1]"));
        assertTrue(ctx.contains("[2]"));
        assertTrue(ctx.contains("First chunk"));
        assertTrue(ctx.contains("Second chunk"));
    }

    @Test
    void deduplicatesAcrossWaves() {
        ContextAccumulator acc = new ContextAccumulator(1000);
        Chunk c1 = chunk("c1", "Shared content");

        int added1 = acc.addWave(List.of(c1), "scout", c -> 0.5);
        assertEquals(1, added1);

        int added2 = acc.addWave(List.of(c1), "dense", c -> 0.9);
        assertEquals(0, added2); // Already present
        assertEquals(1, acc.chunkCount());

        // Source stage should be updated
        var citations = acc.buildCitations();
        assertEquals(1, citations.size());
    }

    @Test
    void updatesRelevanceScoreWhenBetterWaveArrives() {
        ContextAccumulator acc = new ContextAccumulator(1000);
        Chunk c1 = chunk("c1", "Important content");

        acc.addWave(List.of(c1), "scout", c -> 0.5);
        acc.addWave(List.of(c1), "rerank", c -> 0.95);

        var citations = acc.buildCitations();
        assertEquals(1, citations.size());
        assertEquals(0.95, citations.get(0).relevanceScore(), 0.001);
    }

    @Test
    void enforcesTokenBudget() {
        // Very small budget to force truncation
        ContextAccumulator acc = new ContextAccumulator(20, 1);
        List<Chunk> chunks = List.of(
            chunk("c1", "Short"),  // "[1] Short" = 11 chars = 11 tokens, fits
            chunk("c2", "This second chunk should not fit")  // too big
        );

        int added = acc.addWave(chunks, "scout", c -> 0.8);
        assertEquals(1, added, "Only first small chunk should fit in 20-token budget");
        assertEquals(1, acc.chunkCount());
    }

    @Test
    void getNewChunksSinceReturnsOnlyNewOnes() {
        ContextAccumulator acc = new ContextAccumulator(1000);
        Chunk c1 = chunk("c1", "First");
        Chunk c2 = chunk("c2", "Second");

        acc.addWave(List.of(c1), "scout", c -> 0.5);
        Set<String> snapshot = acc.snapshotIds();

        acc.addWave(List.of(c1, c2), "dense", c -> 0.8);
        List<Chunk> newChunks = acc.getNewChunksSince(snapshot);

        assertEquals(1, newChunks.size());
        assertEquals("c2", newChunks.get(0).id());
    }

    @Test
    void citationIndicesAreSequential() {
        ContextAccumulator acc = new ContextAccumulator(1000);
        acc.addWave(List.of(chunk("c1", "A")), "scout", c -> 0.5);
        acc.addWave(List.of(chunk("c2", "B")), "dense", c -> 0.6);
        acc.addWave(List.of(chunk("c3", "C")), "rerank", c -> 0.7);

        var citations = acc.buildCitations();
        assertEquals(3, citations.size());
        assertEquals(1, citations.get(0).index());
        assertEquals(2, citations.get(1).index());
        assertEquals(3, citations.get(2).index());
    }

    @Test
    void remainingTokensReflectsBudget() {
        ContextAccumulator acc = new ContextAccumulator(100, 1);
        assertEquals(100, acc.remainingTokens());

        acc.addWave(List.of(chunk("c1", "Hello world")), "scout", c -> 0.5);
        assertTrue(acc.remainingTokens() < 100);
        assertTrue(acc.remainingTokens() > 0);
    }
}
