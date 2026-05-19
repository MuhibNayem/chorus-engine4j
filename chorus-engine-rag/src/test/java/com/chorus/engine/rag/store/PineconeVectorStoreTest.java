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

class PineconeVectorStoreTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void storeName() {
        PineconeVectorStore store = new PineconeVectorStore("key", "idx.pinecone.io", "ns");
        assertEquals("pinecone:https://idx.pinecone.io", store.storeName());
    }

    @Test
    void upsertBatchesChunks() throws Exception {
        MockHttpClient client = new MockHttpClient().withResponse(200, "{\"upsertedCount\":1}");
        PineconeVectorStore store = new PineconeVectorStore("key", "idx.pinecone.io", "ns", client);

        Chunk c1 = new Chunk("c1", "d1", "hello", 0, 1, null, Map.of("lang", "en"))
            .withEmbedding(new float[]{1.0f, 0.0f, 0.0f});

        store.upsert(List.of(c1));

        assertEquals(1, client.requests.size());
        HttpRequest req = client.requests.get(0);
        assertEquals("POST", req.method());
        assertTrue(req.uri().toString().endsWith("/vectors/upsert"));
        assertTrue(req.headers().firstValue("Api-Key").isPresent());

        String json = extractBody(req);
        JsonNode root = mapper.readTree(json);
        assertTrue(root.has("vectors"));
        assertEquals(1, root.get("vectors").size());
        assertEquals("ns", root.get("namespace").asText());
    }

    @Test
    void upsertWithoutNamespaceOmitsField() throws Exception {
        MockHttpClient client = new MockHttpClient().withResponse(200, "{\"upsertedCount\":1}");
        PineconeVectorStore store = new PineconeVectorStore("key", "idx.pinecone.io", "", client);

        Chunk c1 = new Chunk("c1", "d1", "hello", 0, 1, null, Map.of())
            .withEmbedding(new float[]{1.0f, 0.0f, 0.0f});
        store.upsert(List.of(c1));

        String json = extractBody(client.requests.get(0));
        JsonNode root = mapper.readTree(json);
        assertFalse(root.has("namespace"));
    }

    @Test
    void upsertRejectsMissingEmbedding() {
        MockHttpClient client = new MockHttpClient();
        PineconeVectorStore store = new PineconeVectorStore("key", "idx.pinecone.io", null, client);
        Chunk c1 = new Chunk("c1", "d1", "hello", 0, 1, null, Map.of());
        assertThrows(IllegalArgumentException.class, () -> store.upsert(List.of(c1)));
    }

    @Test
    void searchReturnsResults() throws Exception {
        String queryResponse = """
            {
              "matches": [
                {
                  "id": "c1",
                  "score": 0.95,
                  "metadata": {
                    "document_id": "d1",
                    "text": "hello",
                    "index": 0,
                    "token_count": 1,
                    "chunk_metadata": {"lang": "en"}
                  }
                }
              ]
            }
            """;

        MockHttpClient client = new MockHttpClient().withResponse(200, queryResponse);
        PineconeVectorStore store = new PineconeVectorStore("key", "idx.pinecone.io", "ns", client);

        List<VectorStore.RetrievalResult> results = store.search(new float[]{1.0f, 0.0f, 0.0f}, 5, Map.of());

        assertEquals(1, results.size());
        assertEquals("c1", results.get(0).chunk().id());
        assertEquals(0.95, results.get(0).score(), 0.001);
        assertEquals("en", results.get(0).chunk().metadata().get("lang"));
    }

    @Test
    void searchWithFilters() throws Exception {
        MockHttpClient client = new MockHttpClient().withResponse(200, "{\"matches\":[]}");
        PineconeVectorStore store = new PineconeVectorStore("key", "idx.pinecone.io", "ns", client);

        store.search(new float[]{1.0f, 0.0f, 0.0f}, 5, Map.of("lang", "en"));

        String json = extractBody(client.requests.get(0));
        JsonNode root = mapper.readTree(json);
        assertTrue(root.has("filter"));
        assertEquals("en", root.get("filter").get("lang").asText());
    }

    @Test
    void searchWithTopKZeroReturnsEmpty() {
        MockHttpClient client = new MockHttpClient();
        PineconeVectorStore store = new PineconeVectorStore("key", "idx.pinecone.io", "ns", client);
        assertTrue(store.search(new float[]{1.0f}, 0, Map.of()).isEmpty());
    }

    @Test
    void deleteSendsIds() throws Exception {
        MockHttpClient client = new MockHttpClient().withResponse(200, "{}");
        PineconeVectorStore store = new PineconeVectorStore("key", "idx.pinecone.io", "ns", client);

        store.delete(Set.of("c1", "c2"));

        String json = extractBody(client.requests.get(0));
        JsonNode root = mapper.readTree(json);
        assertEquals(2, root.get("ids").size());
        assertEquals("ns", root.get("namespace").asText());
    }

    @Test
    void deleteEmptySetIsNoOp() {
        MockHttpClient client = new MockHttpClient();
        PineconeVectorStore store = new PineconeVectorStore("key", "idx.pinecone.io", "ns", client);
        store.delete(Set.of());
        assertTrue(client.requests.isEmpty());
    }

    @Test
    void deleteByDocumentSendsFilter() throws Exception {
        MockHttpClient client = new MockHttpClient().withResponse(200, "{}");
        PineconeVectorStore store = new PineconeVectorStore("key", "idx.pinecone.io", "ns", client);

        store.deleteByDocument("d1");

        String json = extractBody(client.requests.get(0));
        JsonNode root = mapper.readTree(json);
        assertEquals("d1", root.get("filter").get("document_id").asText());
    }

    @Test
    void countReturnsZeroWhenNoNamespaceMatch() {
        MockHttpClient client = new MockHttpClient().withResponse(200,
            "{\"namespaces\":{\"other\":{\"vectorCount\":10}}}");
        PineconeVectorStore store = new PineconeVectorStore("key", "idx.pinecone.io", "ns", client);
        assertEquals(0L, store.count());
    }

    @Test
    void countReturnsValueForMatchingNamespace() {
        MockHttpClient client = new MockHttpClient().withResponse(200,
            "{\"namespaces\":{\"ns\":{\"vectorCount\":10}}}");
        PineconeVectorStore store = new PineconeVectorStore("key", "idx.pinecone.io", "ns", client);
        assertEquals(10L, store.count());
    }

    @Test
    void indexHostGetsHttpsPrefix() {
        PineconeVectorStore store = new PineconeVectorStore("key", "host.pinecone.io", null);
        assertTrue(store.storeName().startsWith("pinecone:https://"));
    }

    @Test
    void indexHostWithExistingProtocolIsPreserved() {
        PineconeVectorStore store = new PineconeVectorStore("key", "http://host.pinecone.io", null);
        assertEquals("pinecone:http://host.pinecone.io", store.storeName());
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
