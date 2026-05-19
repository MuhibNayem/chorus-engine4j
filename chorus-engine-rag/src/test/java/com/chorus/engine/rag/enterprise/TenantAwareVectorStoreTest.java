package com.chorus.engine.rag.enterprise;

import com.chorus.engine.core.vector.VectorOperations;
import com.chorus.engine.rag.document.Chunk;
import com.chorus.engine.rag.store.VectorStore;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.*;

class TenantAwareVectorStoreTest {

    @Test
    void upsertAndSearch_perTenantIsolation_soft() {
        VectorOperations vectorOps = VectorOperations.autoDetect();
        TenantIsolation soft = new TenantIsolation("t1", TenantIsolation.IsolationLevel.LOGICAL, "soft_");
        TenantAwareVectorStore store = new TenantAwareVectorStore(vectorOps, soft);

        Chunk c1 = new Chunk("c1", "d1", "tenant a text", 0, 1, null, Map.of("tenantId", "tenant-a"));
        Chunk c2 = new Chunk("c2", "d2", "tenant b text", 0, 1, null, Map.of("tenantId", "tenant-b"));

        store.upsert(List.of(c1, c2));

        float[] query = {1.0f, 0.0f, 0.0f};
        List<VectorStore.RetrievalResult> resultsA = store.search(query, 10, Map.of("_tenantId", "tenant-a"));
        List<VectorStore.RetrievalResult> resultsB = store.search(query, 10, Map.of("_tenantId", "tenant-b"));

        assertThat(resultsA).hasSize(1);
        assertThat(resultsA.get(0).chunk().id()).isEqualTo("c1");
        assertThat(resultsB).hasSize(1);
        assertThat(resultsB.get(0).chunk().id()).isEqualTo("c2");
    }

    @Test
    void upsertAndSearch_perTenantIsolation_hard() {
        VectorOperations vectorOps = VectorOperations.autoDetect();
        TenantIsolation hard = new TenantIsolation("t1", TenantIsolation.IsolationLevel.PHYSICAL, "hard_");
        TenantAwareVectorStore store = new TenantAwareVectorStore(vectorOps, hard);

        Chunk c1 = new Chunk("c1", "d1", "tenant a text", 0, 1, null, Map.of("tenantId", "tenant-a"));
        store.upsert(List.of(c1));

        float[] query = {1.0f, 0.0f, 0.0f};
        List<VectorStore.RetrievalResult> results = store.search(query, 10, Map.of("_tenantId", "tenant-b"));

        assertThat(results).isEmpty();
    }

    @Test
    void defaultTenant_whenNoTenantIdInMetadata() {
        VectorOperations vectorOps = VectorOperations.autoDetect();
        TenantIsolation iso = new TenantIsolation("t1", TenantIsolation.IsolationLevel.LOGICAL, "pre_");
        TenantAwareVectorStore store = new TenantAwareVectorStore(vectorOps, iso);

        Chunk c1 = new Chunk("c1", "d1", "default tenant text", 0, 1, null, Map.of());
        store.upsert(List.of(c1));

        float[] query = {1.0f, 0.0f, 0.0f};
        List<VectorStore.RetrievalResult> results = store.search(query, 10, Map.of());

        assertThat(results).hasSize(1);
        assertThat(results.get(0).chunk().id()).isEqualTo("c1");
    }

    @Test
    void count_sumsAcrossAllTenants() {
        VectorOperations vectorOps = VectorOperations.autoDetect();
        TenantIsolation iso = new TenantIsolation("t1", TenantIsolation.IsolationLevel.LOGICAL, "pre_");
        TenantAwareVectorStore store = new TenantAwareVectorStore(vectorOps, iso);

        Chunk c1 = new Chunk("c1", "d1", "text", 0, 1, null, Map.of("tenantId", "tenant-a"));
        Chunk c2 = new Chunk("c2", "d2", "text", 0, 1, null, Map.of("tenantId", "tenant-b"));
        store.upsert(List.of(c1, c2));

        assertThat(store.count()).isEqualTo(2);
    }

    @Test
    void delete_deletesAcrossAllTenants() {
        VectorOperations vectorOps = VectorOperations.autoDetect();
        TenantIsolation iso = new TenantIsolation("t1", TenantIsolation.IsolationLevel.LOGICAL, "pre_");
        TenantAwareVectorStore store = new TenantAwareVectorStore(vectorOps, iso);

        Chunk c1 = new Chunk("c1", "d1", "text", 0, 1, null, Map.of("tenantId", "tenant-a"));
        store.upsert(List.of(c1));
        assertThat(store.count()).isEqualTo(1);

        store.delete(Set.of("c1"));
        assertThat(store.count()).isEqualTo(0);
    }

    @Test
    void deleteByDocument_deletesAcrossAllTenants() {
        VectorOperations vectorOps = VectorOperations.autoDetect();
        TenantIsolation iso = new TenantIsolation("t1", TenantIsolation.IsolationLevel.LOGICAL, "pre_");
        TenantAwareVectorStore store = new TenantAwareVectorStore(vectorOps, iso);

        Chunk c1 = new Chunk("c1", "d1", "text", 0, 1, null, Map.of("tenantId", "tenant-a"));
        store.upsert(List.of(c1));

        store.deleteByDocument("d1");
        assertThat(store.count()).isEqualTo(0);
    }

    @Test
    void storeName_containsIsolationLevel() {
        VectorOperations vectorOps = VectorOperations.autoDetect();
        TenantIsolation iso = new TenantIsolation("t1", TenantIsolation.IsolationLevel.LOGICAL, "pre_");
        TenantAwareVectorStore store = new TenantAwareVectorStore(vectorOps, iso);

        assertThat(store.storeName()).contains("logical");
    }

    @Test
    void nullRejection() {
        VectorOperations vectorOps = VectorOperations.autoDetect();
        TenantIsolation iso = new TenantIsolation("t1", TenantIsolation.IsolationLevel.LOGICAL, "pre_");
        TenantAwareVectorStore store = new TenantAwareVectorStore(vectorOps, iso);

        assertThatNullPointerException().isThrownBy(() -> store.upsert(null));
        assertThatNullPointerException().isThrownBy(() -> store.search(new float[]{1.0f}, 5, null));
        assertThatNullPointerException().isThrownBy(() -> store.delete(null));
        assertThatNullPointerException().isThrownBy(() -> store.deleteByDocument(null));
    }
}
