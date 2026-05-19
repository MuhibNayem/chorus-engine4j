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

class MilvusVectorStoreTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void storeName() {
        MilvusVectorStore store = new MilvusVectorStore("http://localhost:19530", "test", null);
        assertEquals("milvus:test", store.storeName());
    }

    @Test
    void upsertBatchesChunks() throws Exception {
        MockHttpClient client = new MockHttpClient().withResponse(200, "{\"code\":0}");
        MilvusVectorStore store = new MilvusVectorStore("http://localhost:19530", "test", null, client);

        Chunk c1 = new Chunk("c1", "d1", "hello", 0, 1, null, Map.of("lang", "en"))
            .withEmbedding(new float[]{1.0f, 0.0f, 0.0f});

        store.upsert(List.of(c1));

        assertEquals(1, client.requests.size());
        HttpRequest req = client.requests.get(0);
        assertEquals("POST", req.method());
        assertTrue(req.uri().toString().contains("/v2/vectordb/entities/insert"));

        String json = extractBody(req);
        JsonNode root = mapper.readTree(json);
        assertEquals("test", root.get("collectionName").asText());
        assertEquals(1, root.get("data").size());
        assertEquals("c1", root.get("data").get(0).get("id").asText());
    }

    @Test
    void upsertRejectsMissingEmbedding() {
        MockHttpClient client = new MockHttpClient();
        MilvusVectorStore store = new MilvusVectorStore("http://localhost:19530", "test", null, client);
        Chunk c1 = new Chunk("c1", "d1", "hello", 0, 1, null, Map.of());
        assertThrows(IllegalArgumentException.class, () -> store.upsert(List.of(c1)));
    }

    @Test
    void searchReturnsResults() throws Exception {
        String searchResponse = """
            {
              "data": [
                {
                  "id": "c1",
                  "distance": 0.05,
                  "document_id": "d1",
                  "text": "hello",
                  "index": 0,
                  "token_count": 1,
                  "metadata": {"lang": "en"}
                }
              ]
            }
            """;

        MockHttpClient client = new MockHttpClient().withResponse(200, searchResponse);
        MilvusVectorStore store = new MilvusVectorStore("http://localhost:19530", "test", null, client);

        List<VectorStore.RetrievalResult> results = store.search(new float[]{1.0f, 0.0f, 0.0f}, 5, Map.of());

        assertEquals(1, results.size());
        assertEquals("c1", results.get(0).chunk().id());
        assertEquals(0.05, results.get(0).score(), 0.001);
    }

    @Test
    void searchWithTopKZeroReturnsEmpty() {
        MockHttpClient client = new MockHttpClient();
        MilvusVectorStore store = new MilvusVectorStore("http://localhost:19530", "test", null, client);
        assertTrue(store.search(new float[]{1.0f}, 0, Map.of()).isEmpty());
    }

    @Test
    void searchWithFiltersIncludesExpr() throws Exception {
        MockHttpClient client = new MockHttpClient().withResponse(200, "{\"data\":[]}");
        MilvusVectorStore store = new MilvusVectorStore("http://localhost:19530", "test", null, client);

        store.search(new float[]{1.0f, 0.0f, 0.0f}, 5, Map.of("lang", "en"));

        String json = extractBody(client.requests.get(0));
        JsonNode root = mapper.readTree(json);
        assertTrue(root.has("filter"));
        String filter = root.get("filter").asText();
        assertTrue(filter.contains("metadata['lang']"));
        assertTrue(filter.contains("'en'"));
    }

    @Test
    void deleteSendsExprWithIds() throws Exception {
        MockHttpClient client = new MockHttpClient().withResponse(200, "{\"code\":0}");
        MilvusVectorStore store = new MilvusVectorStore("http://localhost:19530", "test", null, client);

        store.delete(Set.of("c1", "c2"));

        String json = extractBody(client.requests.get(0));
        JsonNode root = mapper.readTree(json);
        String filter = root.get("filter").asText();
        assertTrue(filter.contains("id in ["));
        assertTrue(filter.contains("'c1'"));
        assertTrue(filter.contains("'c2'"));
    }

    @Test
    void deleteEmptySetIsNoOp() {
        MockHttpClient client = new MockHttpClient();
        MilvusVectorStore store = new MilvusVectorStore("http://localhost:19530", "test", null, client);
        store.delete(Set.of());
        assertTrue(client.requests.isEmpty());
    }

    @Test
    void deleteByDocumentSendsExpr() throws Exception {
        MockHttpClient client = new MockHttpClient().withResponse(200, "{\"code\":0}");
        MilvusVectorStore store = new MilvusVectorStore("http://localhost:19530", "test", null, client);

        store.deleteByDocument("d1");

        String json = extractBody(client.requests.get(0));
        JsonNode root = mapper.readTree(json);
        String filter = root.get("filter").asText();
        assertEquals("document_id == 'd1'", filter);
    }

    @Test
    void countReturnsZero() {
        MockHttpClient client = new MockHttpClient().withResponse(200, "{}");
        MilvusVectorStore store = new MilvusVectorStore("http://localhost:19530", "test", null, client);
        assertEquals(0L, store.count());
    }

    @Test
    void createCollectionSendsCorrectPayload() throws Exception {
        MockHttpClient client = new MockHttpClient().withResponse(200, "{\"code\":0}");
        MilvusVectorStore store = new MilvusVectorStore("http://localhost:19530", "test", null, client);

        store.createCollection(768, "COSINE");

        assertEquals(1, client.requests.size());
        HttpRequest req = client.requests.get(0);
        assertTrue(req.uri().toString().contains("/collections/create"));

        String json = extractBody(req);
        JsonNode root = mapper.readTree(json);
        assertEquals("test", root.get("collectionName").asText());
        assertEquals(768, root.get("fields").get(7).get("elementTypeParams").get("dim").asInt());
    }

    @Test
    void tokenIsSentWhenProvided() {
        MockHttpClient client = new MockHttpClient().withResponse(200, "{\"code\":0}");
        MilvusVectorStore store = new MilvusVectorStore("http://localhost:19530", "test", "my-token", client);

        Chunk c1 = new Chunk("c1", "d1", "hello", 0, 1, null, Map.of())
            .withEmbedding(new float[]{1.0f, 0.0f, 0.0f});
        store.upsert(List.of(c1));

        HttpRequest req = client.requests.get(0);
        assertTrue(req.headers().firstValue("Authorization").isPresent());
        assertEquals("Bearer my-token", req.headers().firstValue("Authorization").get());
    }

    @Test
    void baseUrlTrailingSlashIsStripped() {
        MilvusVectorStore store = new MilvusVectorStore("http://localhost:19530/", "test", null);
        assertEquals("milvus:test", store.storeName());
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
