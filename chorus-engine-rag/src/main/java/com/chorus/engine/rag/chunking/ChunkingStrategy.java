package com.chorus.engine.rag.chunking;

import com.chorus.engine.rag.document.Chunk;
import com.chorus.engine.rag.document.Document;
import org.jspecify.annotations.NonNull;

import java.util.List;

/**
 * Pluggable document chunking strategy.
 *
 * <p>2026 strategies:
 * <ul>
 *   <li>{@link FixedSizeChunking} — fixed token/word size with overlap</li>
 *   <li>{@link RecursiveChunking} — hierarchical: paragraphs → sentences → words</li>
 *   <li>{@link SemanticChunking} — split at semantic boundaries using embeddings</li>
 *   <li>{@link ParentChildChunking} — small child chunks with large parent context</li>
 * </ul>
 */
public interface ChunkingStrategy {

    @NonNull List<Chunk> chunk(@NonNull Document document);

    @NonNull String name();
}
