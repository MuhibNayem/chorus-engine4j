package com.chorus.engine.rag.retrieval;

import com.chorus.engine.rag.document.Chunk;
import org.jspecify.annotations.NonNull;

import java.util.List;
import java.util.Map;

/**
 * Pluggable retrieval engine.
 */
public interface RetrievalEngine {

    @NonNull List<RetrievalResult> retrieve(@NonNull String query, @NonNull RetrieveOptions options);

    record RetrievalResult(
        @NonNull Chunk chunk,
        double score,
        @NonNull String sourceEngine // "dense", "sparse", "graph", "web"
    ) {}

    record RetrieveOptions(
        int topK,
        @NonNull Map<String, Object> filters,
        boolean includeMetadata
    ) {
        public RetrieveOptions {
            filters = Map.copyOf(filters);
        }
        public static @NonNull RetrieveOptions defaults(int topK) {
            return new RetrieveOptions(topK, Map.of(), true);
        }
    }
}
