package com.chorus.engine.rag.chunking;

import com.chorus.engine.rag.document.Chunk;
import com.chorus.engine.rag.document.Document;
import org.jspecify.annotations.NonNull;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Fixed-size chunking with configurable overlap.
 * Production default for most corpora.
 */
public final class FixedSizeChunking implements ChunkingStrategy {

    private final int chunkSize;
    private final int overlap;
    private final String unit; // "chars" or "tokens"

    public FixedSizeChunking(int chunkSize, int overlap) {
        this(chunkSize, overlap, "chars");
    }

    public FixedSizeChunking(int chunkSize, int overlap, @NonNull String unit) {
        if (chunkSize <= 0) throw new IllegalArgumentException("chunkSize must be > 0");
        if (overlap < 0 || overlap >= chunkSize) throw new IllegalArgumentException("overlap must be in [0, chunkSize)");
        this.chunkSize = chunkSize;
        this.overlap = overlap;
        this.unit = unit;
    }

    @Override
    public @NonNull List<Chunk> chunk(@NonNull Document document) {
        List<Chunk> chunks = new ArrayList<>();
        String text = document.content();
        int pos = 0;
        int index = 0;

        while (pos < text.length()) {
            int end = Math.min(pos + chunkSize, text.length());
            // Try to break at word boundary
            if (end < text.length()) {
                int lastSpace = text.lastIndexOf(' ', end);
                if (lastSpace > pos) end = lastSpace;
            }

            String chunkText = text.substring(pos, end).trim();
            if (!chunkText.isEmpty()) {
                chunks.add(new Chunk(
                    UUID.randomUUID().toString(),
                    document.id(),
                    chunkText,
                    index++,
                    estimateTokens(chunkText),
                    null,
                    java.util.Map.of("source", document.source(), "start", pos, "end", end)
                ));
            }
            if (end >= text.length()) break;
            pos = end - overlap;
            if (pos <= 0) break;
        }
        return chunks;
    }

    private int estimateTokens(@NonNull String text) {
        // Rough estimate: ~4 chars per token for English
        return text.length() / 4;
    }

    @Override
    public @NonNull String name() { return "fixed_size_" + chunkSize + "_" + overlap; }
}
