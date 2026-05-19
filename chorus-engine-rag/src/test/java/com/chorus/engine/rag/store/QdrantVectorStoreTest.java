package com.chorus.engine.rag.store;

import com.chorus.engine.rag.document.Chunk;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.net.http.HttpRequest;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class QdrantVectorStoreTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void storeName() {
        QdrantVectorStore store = new QdrantVectorStore("http://localhost:6333", "test", null);
        assertEquals("qdrant:test", store.storeName());
    }

    @Test
    void upsertBatchesChunks() throws Exception {
        MockHttpClient client = new MockHttpClient().withResponse(200, "{\"status\":\"ok\"}");
        QdrantVectorStore store = new QdrantVectorStore("http://localhost:6333", "test", null, client);

        Chunk c1 = new Chunk("c1", "d1", "hello", 0, 1, null, Map.of("lang", "en"))
            .withEmbedding(new float[]{1.0f, 0.0f, 0.0f});

        store.upsert(List.of(c1));

        assertEquals(1, client.requests.size());
        HttpRequest req = client.requests.get(0);
        assertEquals("PUT", req.method());
        assertTrue(req.uri().toString().contains("/collections/test/points"));

        String json = extractBody(req);
        JsonNode root = mapper.readTree(json);
        assertTrue(root.has("points"));
        assertEquals(1, root.get("points").size());
        assertEquals("c1", root.get("points").get(0).get("id").asText());
    }

    @Test
    void upsertRejectsMissingEmbedding() {
        MockHttpClient client = new MockHttpClient();
        QdrantVectorStore store = new QdrantVectorStore("http://localhost:6333", "test", null, client);

        Chunk c1 = new Chunk("c1", "d1", "hello", 0, 1, null, Map.of());
        assertThrows(IllegalArgumentException.class, () -> store.upsert(List.of(c1)));
    }

    @Test
    void searchReturnsResults() throws Exception {
        String searchResponse = """
            {
              "result": [
                {
                  "id": "c1",
                  "score": 0.95,
                  "payload": {
                    "document_id": "d1",
                    "text": "hello",
                    "index": 0,
                    "token_count": 1,
                    "parent_chunk_id": null,
                    "metadata": {"lang": "en"}
                  }
                }
              ]
            }
            """;

        MockHttpClient client = new MockHttpClient().withResponse(200, searchResponse);
        QdrantVectorStore store = new QdrantVectorStore("http://localhost:6333", "test", null, client);

        List<VectorStore.RetrievalResult> results = store.search(new float[]{1.0f, 0.0f, 0.0f}, 5, Map.of());

        assertEquals(1, results.size());
        assertEquals("c1", results.get(0).chunk().id());
        assertEquals(0.95, results.get(0).score(), 0.001);
    }

    @Test
    void searchWithTopKZeroReturnsEmpty() {
        MockHttpClient client = new MockHttpClient();
        QdrantVectorStore store = new QdrantVectorStore("http://localhost:6333", "test", null, client);
        assertTrue(store.search(new float[]{1.0f}, 0, Map.of()).isEmpty());
    }

    @Test
    void deleteSendsCorrectRequest() throws Exception {
        MockHttpClient client = new MockHttpClient().withResponse(200, "{\"status\":\"ok\"}");
        QdrantVectorStore store = new QdrantVectorStore("http://localhost:6333", "test", null, client);

        store.delete(Set.of("c1", "c2"));

        assertEquals(1, client.requests.size());
        HttpRequest req = client.requests.get(0);
        String json = extractBody(req);
        JsonNode root = mapper.readTree(json);
        assertEquals(2, root.get("points").size());
    }

    @Test
    void deleteEmptySetIsNoOp() {
        MockHttpClient client = new MockHttpClient();
        QdrantVectorStore store = new QdrantVectorStore("http://localhost:6333", "test", null, client);
        store.delete(Set.of());
        assertTrue(client.requests.isEmpty());
    }

    @Test
    void deleteByDocumentSendsFilter() throws Exception {
        MockHttpClient client = new MockHttpClient().withResponse(200, "{\"status\":\"ok\"}");
        QdrantVectorStore store = new QdrantVectorStore("http://localhost:6333", "test", null, client);

        store.deleteByDocument("d1");

        assertEquals(1, client.requests.size());
        HttpRequest req = client.requests.get(0);
        String json = extractBody(req);
        JsonNode root = mapper.readTree(json);
        assertTrue(root.has("filter"));
    }

    @Test
    void countParsesPointsCount() {
        MockHttpClient client = new MockHttpClient().withResponse(200,
            "{\"result\":{\"points_count\":42}}");
        QdrantVectorStore store = new QdrantVectorStore("http://localhost:6333", "test", null, client);

        assertEquals(42L, store.count());
    }

    @Test
    void apiKeyIsSentWhenProvided() {
        MockHttpClient client = new MockHttpClient().withResponse(200, "{\"status\":\"ok\"}");
        QdrantVectorStore store = new QdrantVectorStore("http://localhost:6333", "test", "secret", client);

        Chunk c1 = new Chunk("c1", "d1", "hello", 0, 1, null, Map.of())
            .withEmbedding(new float[]{1.0f, 0.0f, 0.0f});
        store.upsert(List.of(c1));

        HttpRequest req = client.requests.get(0);
        assertTrue(req.headers().firstValue("api-key").isPresent());
        assertEquals("secret", req.headers().firstValue("api-key").get());
    }

    @Test
    void baseUrlTrailingSlashIsStripped() {
        QdrantVectorStore store = new QdrantVectorStore("http://localhost:6333/", "test", null);
        assertEquals("qdrant:test", store.storeName());
    }

    private static String extractBody(HttpRequest req) {
        var ref = new Object() { String value = ""; };
        req.bodyPublisher().ifPresent(p -> {
            p.subscribe(new java.util.concurrent.Flow.Subscriber<java.nio.ByteBuffer>() {
                @Override public void onSubscribe(java.util.concurrent.Flow.Subscription s) { s.request(Long.MAX_VALUE); }
                @Override public void onNext(java.nio.ByteBuffer item) {
                    byte[] bytes = new byte[item.remaining()];
                    item.get(bytes);
                    ref.value += new String(bytes, java.nio.charset.StandardCharsets.UTF_8);
                }
                @Override public void onError(Throwable t) {}
                @Override public void onComplete() {}
            });
        });
        try { Thread.sleep(20); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        return ref.value;
    }
}
