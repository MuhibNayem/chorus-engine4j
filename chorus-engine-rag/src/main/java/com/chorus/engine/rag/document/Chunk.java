package com.chorus.engine.rag.document;

import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

import java.util.Map;
import java.util.Objects;

/**
 * Immutable chunk of a document. The atomic unit of retrieval.
 */
public record Chunk(
    @NonNull String id,
    @NonNull String documentId,
    @NonNull String text,
    int index, // position within document
    int tokenCount,
    @Nullable String parentChunkId, // for parent-child chunking
    @NonNull Map<String, Object> metadata
) {
    public Chunk {
        Objects.requireNonNull(id);
        Objects.requireNonNull(documentId);
        Objects.requireNonNull(text);
        metadata = Map.copyOf(metadata);
    }

    public @NonNull Chunk withEmbedding(float @Nullable [] embedding) {
        Map<String, Object> newMeta = new java.util.HashMap<>(metadata);
        if (embedding != null) newMeta.put("_embedding", embedding);
        return new Chunk(id, documentId, text, index, tokenCount, parentChunkId, newMeta);
    }

    public float @Nullable [] embedding() {
        Object emb = metadata.get("_embedding");
        return emb instanceof float[] f ? f : null;
    }
}
