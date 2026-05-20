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
    private final Map<String, String> chunkIdToTenantId = new ConcurrentHashMap<>();
    private final Map<String, Set<String>> documentIdToTenantIds = new ConcurrentHashMap<>();
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
            String tenantId = entry.getKey();
            getStore(tenantId).upsert(entry.getValue());
            for (Chunk chunk : entry.getValue()) {
                chunkIdToTenantId.put(chunk.id(), tenantId);
                documentIdToTenantIds.computeIfAbsent(chunk.documentId(), k -> ConcurrentHashMap.newKeySet()).add(tenantId);
            }
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
        Map<String, Set<String>> byTenant = new HashMap<>();
        for (String chunkId : chunkIds) {
            String tenantId = chunkIdToTenantId.get(chunkId);
            if (tenantId != null) {
                byTenant.computeIfAbsent(tenantId, k -> ConcurrentHashMap.newKeySet()).add(chunkId);
            }
        }
        for (Map.Entry<String, Set<String>> entry : byTenant.entrySet()) {
            getStore(entry.getKey()).delete(entry.getValue());
        }
        for (String chunkId : chunkIds) {
            chunkIdToTenantId.remove(chunkId);
        }
    }

    @Override
    public void deleteByDocument(@NonNull String documentId) {
        Objects.requireNonNull(documentId, "documentId");
        Set<String> tenantIds = documentIdToTenantIds.remove(documentId);
        if (tenantIds != null) {
            for (String tenantId : tenantIds) {
                VectorStore store = tenantStores.get(tenantId);
                if (store != null) {
                    store.deleteByDocument(documentId);
                }
            }
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
