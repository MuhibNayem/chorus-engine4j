package com.chorus.engine.rag.enterprise;

import com.chorus.engine.core.vector.VectorOperations;
import com.chorus.engine.rag.document.Chunk;
import com.chorus.engine.rag.store.VectorStore;
import org.jspecify.annotations.NonNull;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Multi-tenant in-memory vector store.
 *
 * <p>Each tenant gets an isolated {@link InMemoryVectorStore} instance.
 * For production, replace with tenant-aware pgvector, Pinecone, or Qdrant
 * implementations that use collection prefixes or namespaces.
 */
public final class TenantAwareVectorStore implements VectorStore {

    private final Map<String, VectorStore> tenantStores = new ConcurrentHashMap<>();
    private final VectorOperations vectorOps;
    private final TenantIsolation isolation;

    public TenantAwareVectorStore(@NonNull VectorOperations vectorOps, @NonNull TenantIsolation isolation) {
        this.vectorOps = vectorOps;
        this.isolation = isolation;
    }

    @Override
    public void upsert(@NonNull List<Chunk> chunks) {
        Objects.requireNonNull(chunks, "chunks");
        Map<String, List<Chunk>> byTenant = new HashMap<>();
        for (Chunk chunk : chunks) {
            String tenantId = extractTenant(List.of(chunk));
            byTenant.computeIfAbsent(tenantId, k -> new ArrayList<>()).add(chunk);
        }
        for (Map.Entry<String, List<Chunk>> entry : byTenant.entrySet()) {
            getStore(entry.getKey()).upsert(entry.getValue());
        }
    }

    @Override
    public @NonNull List<RetrievalResult> search(@NonNull float[] queryEmbedding, int topK, @NonNull Map<String, Object> filters) {
        Objects.requireNonNull(filters, "filters");
        String tenantId = filters.getOrDefault("_tenantId", "default").toString();
        Map<String, Object> tenantFilters = new HashMap<>(filters);
        tenantFilters.remove("_tenantId");
        return getStore(tenantId).search(queryEmbedding, topK, tenantFilters);
    }

    @Override
    public void delete(@NonNull Set<String> chunkIds) {
        Objects.requireNonNull(chunkIds, "chunkIds");
        for (VectorStore store : tenantStores.values()) {
            store.delete(chunkIds);
        }
    }

    @Override
    public void deleteByDocument(@NonNull String documentId) {
        Objects.requireNonNull(documentId, "documentId");
        for (VectorStore store : tenantStores.values()) {
            store.deleteByDocument(documentId);
        }
    }

    @Override
    public long count() {
        return tenantStores.values().stream().mapToLong(VectorStore::count).sum();
    }

    @Override
    public @NonNull String storeName() {
        return "tenant_aware_" + isolation.level().name().toLowerCase(Locale.ROOT);
    }

    private @NonNull VectorStore getStore(@NonNull String tenantId) {
        return tenantStores.computeIfAbsent(tenantId, t ->
            new com.chorus.engine.rag.store.InMemoryVectorStore(vectorOps));
    }

    private @NonNull String extractTenant(@NonNull List<Chunk> chunks) {
        if (chunks.isEmpty()) return "default";
        Object tenant = chunks.get(0).metadata().get("tenantId");
        return tenant != null ? tenant.toString() : "default";
    }
}
