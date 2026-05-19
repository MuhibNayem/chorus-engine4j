package com.chorus.engine.rag.document;

import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

import java.time.Instant;
import java.util.Map;
import java.util.Objects;

/**
 * Immutable source document for RAG ingestion.
 */
public record Document(
    @NonNull String id,
    @NonNull String content,
    @NonNull String source,
    @Nullable String title,
    @NonNull Map<String, Object> metadata,
    @NonNull Instant ingestedAt,
    @Nullable Instant updatedAt
) {
    public Document {
        Objects.requireNonNull(id);
        Objects.requireNonNull(content);
        Objects.requireNonNull(source);
        metadata = Map.copyOf(metadata);
        ingestedAt = ingestedAt != null ? ingestedAt : Instant.now();
    }

    public static @NonNull Builder builder(@NonNull String id, @NonNull String content, @NonNull String source) {
        return new Builder(id, content, source);
    }

    public @NonNull Document withContent(@NonNull String newContent) {
        return new Document(id, newContent, source, title, metadata, ingestedAt, Instant.now());
    }

    public static final class Builder {
        private final String id;
        private final String content;
        private final String source;
        private String title;
        private Map<String, Object> metadata = Map.of();
        private Instant ingestedAt = Instant.now();
        private Instant updatedAt;

        Builder(String id, String content, String source) {
            this.id = id;
            this.content = content;
            this.source = source;
        }

        public Builder title(String title) { this.title = title; return this; }
        public Builder metadata(Map<String, Object> metadata) { this.metadata = metadata; return this; }
        public Builder ingestedAt(Instant ingestedAt) { this.ingestedAt = ingestedAt; return this; }
        public Builder updatedAt(Instant updatedAt) { this.updatedAt = updatedAt; return this; }

        public @NonNull Document build() {
            return new Document(id, content, source, title, metadata, ingestedAt, updatedAt);
        }
    }
}
