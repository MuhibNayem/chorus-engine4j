package com.chorus.engine.rag.store;

import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class VectorStoreFactoryTest {

    @Test
    void createMemoryStore() {
        VectorStore store = VectorStoreFactory.create("memory", Map.of());
        assertInstanceOf(InMemoryVectorStore.class, store);
        assertEquals("in_memory", store.storeName());
    }

    @Test
    void createPgVectorStoreRequiresDataSource() {
        IllegalArgumentException ex = assertThrows(
            IllegalArgumentException.class,
            () -> VectorStoreFactory.create("pgvector", Map.of())
        );
        assertTrue(ex.getMessage().contains("dataSource"));
    }

    @Test
    void createPgVectorStoreWithDefaults() throws SQLException {
        DataSource ds = new NoOpDataSource();
        VectorStore store = VectorStoreFactory.create("pgvector", Map.of(
            "dataSource", ds,
            "tableName", "rag_chunks",
            "dimensions", 512,
            "distanceMetric", "l2"
        ));
        assertInstanceOf(PgVectorStore.class, store);
        assertEquals("pgvector:rag_chunks", store.storeName());
    }

    @Test
    void createQdrantStore() {
        VectorStore store = VectorStoreFactory.create("qdrant", Map.of(
            "baseUrl", "http://localhost:6333",
            "collectionName", "my_collection"
        ));
        assertInstanceOf(QdrantVectorStore.class, store);
        assertEquals("qdrant:my_collection", store.storeName());
    }

    @Test
    void createQdrantStoreRequiresBaseUrl() {
        IllegalArgumentException ex = assertThrows(
            IllegalArgumentException.class,
            () -> VectorStoreFactory.create("qdrant", Map.of("collectionName", "test"))
        );
        assertTrue(ex.getMessage().contains("baseUrl"));
    }

    @Test
    void createPineconeStore() {
        VectorStore store = VectorStoreFactory.create("pinecone", Map.of(
            "apiKey", "secret",
            "indexHost", "idx.pinecone.io",
            "namespace", "ns1"
        ));
        assertInstanceOf(PineconeVectorStore.class, store);
        assertTrue(store.storeName().startsWith("pinecone:"));
    }

    @Test
    void createPineconeStoreRequiresApiKey() {
        IllegalArgumentException ex = assertThrows(
            IllegalArgumentException.class,
            () -> VectorStoreFactory.create("pinecone", Map.of("indexHost", "idx.pinecone.io"))
        );
        assertTrue(ex.getMessage().contains("apiKey"));
    }

    @Test
    void createMilvusStore() {
        VectorStore store = VectorStoreFactory.create("milvus", Map.of(
            "baseUrl", "http://localhost:19530",
            "collectionName", "my_collection",
            "token", "root:Milvus"
        ));
        assertInstanceOf(MilvusVectorStore.class, store);
        assertEquals("milvus:my_collection", store.storeName());
    }

    @Test
    void createMilvusStoreRequiresBaseUrl() {
        IllegalArgumentException ex = assertThrows(
            IllegalArgumentException.class,
            () -> VectorStoreFactory.create("milvus", Map.of("collectionName", "test"))
        );
        assertTrue(ex.getMessage().contains("baseUrl"));
    }

    @Test
    void unknownTypeThrows() {
        IllegalArgumentException ex = assertThrows(
            IllegalArgumentException.class,
            () -> VectorStoreFactory.create("nonexistent_db", Map.of())
        );
        assertTrue(ex.getMessage().contains("Unknown"));
    }

    @Test
    void typeIsCaseInsensitive() {
        VectorStore store = VectorStoreFactory.create("MEMORY", Map.of());
        assertInstanceOf(InMemoryVectorStore.class, store);
    }

    // ---- Simple no-op DataSource for testing ----

    static class NoOpDataSource implements DataSource {
        @Override public Connection getConnection() throws SQLException { return new PgVectorStoreTest.NoOpConnection(); }
        @Override public Connection getConnection(String username, String password) throws SQLException { return new PgVectorStoreTest.NoOpConnection(); }
        @Override public java.io.PrintWriter getLogWriter() throws SQLException { return null; }
        @Override public void setLogWriter(java.io.PrintWriter out) throws SQLException {}
        @Override public void setLoginTimeout(int seconds) throws SQLException {}
        @Override public int getLoginTimeout() throws SQLException { return 0; }
        @Override public java.util.logging.Logger getParentLogger() { return null; }
        @Override public <T> T unwrap(Class<T> iface) throws SQLException { return null; }
        @Override public boolean isWrapperFor(Class<?> iface) throws SQLException { return false; }
    }
}
