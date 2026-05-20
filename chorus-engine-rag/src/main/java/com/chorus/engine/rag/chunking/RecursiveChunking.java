package com.chorus.engine.rag.chunking;

import com.chorus.engine.rag.document.Chunk;
import com.chorus.engine.rag.document.Document;
import org.jspecify.annotations.NonNull;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Hierarchical recursive chunking: paragraphs → sentences → words.
 * Tries larger separators first, falls back to smaller ones if chunk too big.
 * Best for documents with clear structure (markdown, HTML, legal text).
 */
public final class RecursiveChunking implements ChunkingStrategy {

    private final int chunkSize;
    private final int overlap;
    private final List<String> separators;

    public RecursiveChunking(int chunkSize, int overlap) {
        this(chunkSize, overlap, List.of(
            "\n\n\n", "\n\n", "\n", ". ", "! ", "? ", " ", ""
        ));
    }

    public RecursiveChunking(int chunkSize, int overlap, @NonNull List<String> separators) {
        if (chunkSize <= 0) throw new IllegalArgumentException("chunkSize must be > 0");
        if (overlap < 0 || overlap >= chunkSize) throw new IllegalArgumentException("overlap invalid");
        this.chunkSize = chunkSize;
        this.overlap = overlap;
        this.separators = List.copyOf(separators);
    }

    @Override
    public @NonNull List<Chunk> chunk(@NonNull Document document) {
        List<Chunk> chunks = new ArrayList<>();
        splitRecursively(document.content(), document, 0, 0, chunks);
        return chunks;
    }

    private int splitRecursively(@NonNull String text, @NonNull Document doc, int sepIndex, int startIndex, @NonNull List<Chunk> chunks) {
        if (text.length() <= chunkSize) {
            if (!text.trim().isEmpty()) {
                chunks.add(new Chunk(UUID.randomUUID().toString(), doc.id(), text.trim(),
                    startIndex, estimateTokens(text), null,
                    java.util.Map.of("source", doc.source())));
            }
            return startIndex + 1;
        }

        if (sepIndex >= separators.size()) {
            // Force split at chunkSize
            String part = text.substring(0, chunkSize);
            if (!part.trim().isEmpty()) {
                chunks.add(new Chunk(UUID.randomUUID().toString(), doc.id(), part.trim(),
                    startIndex, estimateTokens(part), null,
                    java.util.Map.of("source", doc.source())));
            }
            String remainder = text.substring(Math.max(0, chunkSize - overlap));
            return splitRecursively(remainder, doc, 0, startIndex + 1, chunks);
        }

        String sep = separators.get(sepIndex);
        int splitPos = text.lastIndexOf(sep, chunkSize);
        if (splitPos <= 0) {
            return splitRecursively(text, doc, sepIndex + 1, startIndex, chunks);
        }

        String part = text.substring(0, splitPos + sep.length()).trim();
        if (!part.isEmpty()) {
            chunks.add(new Chunk(UUID.randomUUID().toString(), doc.id(), part,
                startIndex, estimateTokens(part), null,
                java.util.Map.of("source", doc.source())));
        }
        String remainder = text.substring(Math.max(0, splitPos + sep.length() - overlap));
        return splitRecursively(remainder, doc, 0, startIndex + 1, chunks);
    }

    private int estimateTokens(@NonNull String text) {
        return text.length() / 4;
    }

    @Override
    public @NonNull String name() { return "recursive_" + chunkSize; }
}
