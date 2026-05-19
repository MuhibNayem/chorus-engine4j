package com.chorus.engine.rag.pipeline;

import com.chorus.engine.core.context.Message;
import com.chorus.engine.core.context.TokenCount;
import com.chorus.engine.core.reactive.CancellationToken;
import com.chorus.engine.core.result.Result;
import com.chorus.engine.llm.*;
import com.chorus.engine.llm.embed.EmbeddingClient;
import com.chorus.engine.rag.chunking.ChunkingStrategy;
import com.chorus.engine.rag.document.Chunk;
import com.chorus.engine.rag.document.Document;
import com.chorus.engine.rag.retrieval.HybridRetrievalEngine;
import com.chorus.engine.rag.retrieval.RetrievalEngine;
import com.chorus.engine.rag.store.InMemoryVectorStore;
import com.chorus.engine.rag.store.VectorStore;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.Flow;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RAGPipelineTest {

    @Test
    void ingestChunksAndStoresDocument() {
        FakeEmbeddingClient embedClient = new FakeEmbeddingClient(3);
        InMemoryVectorStore vectorStore = new InMemoryVectorStore();
        FakeKeywordIndex keywordIndex = new FakeKeywordIndex();
        FakeLlmClient llmClient = new FakeLlmClient();
        llmClient.enqueue(chatResponse("Generated answer"));

        RAGPipeline pipeline = new RAGPipeline(
            new FakeChunkingStrategy(2),
            embedClient,
            vectorStore,
            keywordIndex,
            null,
            null,
            new ContextAssembler(1000),
            llmClient,
            "test-model"
        );

        Document doc = Document.builder("doc-1", "Hello world foo bar", "test").build();
        List<Chunk> chunks = pipeline.ingest(doc);

        assertThat(chunks).hasSize(2);
        assertThat(vectorStore.count()).isEqualTo(2);
        assertThat(keywordIndex.indexedCount()).isEqualTo(2);
    }

    @Test
    void queryReturnsAnswerWithCitations() {
        FakeEmbeddingClient embedClient = new FakeEmbeddingClient(3);
        InMemoryVectorStore vectorStore = new InMemoryVectorStore();
        FakeKeywordIndex keywordIndex = new FakeKeywordIndex();
        FakeLlmClient llmClient = new FakeLlmClient();
        llmClient.enqueue(chatResponse("Paris is the capital of France."));

        RAGPipeline pipeline = new RAGPipeline(
            new FakeChunkingStrategy(1),
            embedClient,
            vectorStore,
            keywordIndex,
            null,
            null,
            new ContextAssembler(1000),
            llmClient,
            "test-model"
        );

        Document doc = Document.builder("doc-1", "Paris is the capital of France.", "test").build();
        pipeline.ingest(doc);

        RAGPipeline.RagResult result = pipeline.query("What is the capital of France?", RetrievalEngine.RetrieveOptions.defaults(5));

        assertThat(result.answer()).isEqualTo("Paris is the capital of France.");
        assertThat(result.citations()).isNotEmpty();
        assertThat(result.originalQuery()).isEqualTo("What is the capital of France?");
    }

    @Test
    void queryWithEmptyVectorStore() {
        FakeEmbeddingClient embedClient = new FakeEmbeddingClient(3);
        InMemoryVectorStore vectorStore = new InMemoryVectorStore();
        FakeKeywordIndex keywordIndex = new FakeKeywordIndex();
        FakeLlmClient llmClient = new FakeLlmClient();
        llmClient.enqueue(chatResponse("I don't have enough information."));

        RAGPipeline pipeline = new RAGPipeline(
            new FakeChunkingStrategy(1),
            embedClient,
            vectorStore,
            keywordIndex,
            null,
            null,
            new ContextAssembler(1000),
            llmClient,
            "test-model"
        );

        RAGPipeline.RagResult result = pipeline.query("What is the capital of France?", RetrievalEngine.RetrieveOptions.defaults(5));

        assertThat(result.answer()).isEqualTo("I don't have enough information.");
        assertThat(result.citations()).isEmpty();
    }

    @Test
    void queryWithFilters() {
        FakeEmbeddingClient embedClient = new FakeEmbeddingClient(3);
        InMemoryVectorStore vectorStore = new InMemoryVectorStore();
        FakeKeywordIndex keywordIndex = new FakeKeywordIndex();
        FakeLlmClient llmClient = new FakeLlmClient();
        llmClient.enqueue(chatResponse("French capital."));

        RAGPipeline pipeline = new RAGPipeline(
            new FakeChunkingStrategy(1),
            embedClient,
            vectorStore,
            keywordIndex,
            null,
            null,
            new ContextAssembler(1000),
            llmClient,
            "test-model"
        );

        Document doc = Document.builder("doc-1", "Paris is the capital of France.", "test")
            .metadata(Map.of("lang", "en"))
            .build();
        pipeline.ingest(doc);

        RAGPipeline.RagResult result = pipeline.query(
            "Capital?",
            new RetrievalEngine.RetrieveOptions(5, Map.of("lang", "en"), true)
        );

        assertThat(result.answer()).isEqualTo("French capital.");
    }

    @Test
    void multipleDocumentIngest() {
        FakeEmbeddingClient embedClient = new FakeEmbeddingClient(3);
        InMemoryVectorStore vectorStore = new InMemoryVectorStore();
        FakeKeywordIndex keywordIndex = new FakeKeywordIndex();
        FakeLlmClient llmClient = new FakeLlmClient();
        llmClient.enqueue(chatResponse("Answer"));

        RAGPipeline pipeline = new RAGPipeline(
            new FakeChunkingStrategy(1),
            embedClient,
            vectorStore,
            keywordIndex,
            null,
            null,
            new ContextAssembler(1000),
            llmClient,
            "test-model"
        );

        pipeline.ingest(Document.builder("doc-1", "Content A", "test").build());
        pipeline.ingest(Document.builder("doc-2", "Content B", "test").build());

        assertThat(vectorStore.count()).isEqualTo(2);
        assertThat(keywordIndex.indexedCount()).isEqualTo(2);
    }

    @Test
    void nullRejection() {
        FakeEmbeddingClient embedClient = new FakeEmbeddingClient(3);
        InMemoryVectorStore vectorStore = new InMemoryVectorStore();
        FakeKeywordIndex keywordIndex = new FakeKeywordIndex();
        FakeLlmClient llmClient = new FakeLlmClient();

        RAGPipeline pipeline = new RAGPipeline(
            new FakeChunkingStrategy(1),
            embedClient,
            vectorStore,
            keywordIndex,
            null,
            null,
            new ContextAssembler(1000),
            llmClient,
            "test-model"
        );

        assertThatThrownBy(() -> pipeline.ingest(null))
            .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> pipeline.query(null, RetrievalEngine.RetrieveOptions.defaults(5)))
            .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> pipeline.query("q", null))
            .isInstanceOf(NullPointerException.class);
    }

    // ---- fakes ----

    static class FakeEmbeddingClient implements EmbeddingClient {
        private final int dimensions;

        FakeEmbeddingClient(int dimensions) {
            this.dimensions = dimensions;
        }

        @Override
        public Result<float[], EmbeddingError> embed(String text, EmbedOptions options) {
            return Result.ok(makeEmbedding(text.hashCode()));
        }

        @Override
        public Result<List<float[]>, EmbeddingError> embedBatch(List<String> texts, EmbedOptions options) {
            return Result.ok(texts.stream().map(t -> makeEmbedding(t.hashCode())).toList());
        }

        private float[] makeEmbedding(int seed) {
            Random r = new Random(seed);
            float[] emb = new float[dimensions];
            for (int i = 0; i < dimensions; i++) emb[i] = r.nextFloat();
            return emb;
        }

        @Override public String providerName() { return "fake"; }
        @Override public String modelName() { return "fake-embed"; }
        @Override public int nativeDimensions() { return dimensions; }
        @Override public boolean isLocal() { return true; }
        @Override public HealthStatus health() { return HealthStatus.HEALTHY; }
    }

    static class FakeKeywordIndex implements HybridRetrievalEngine.KeywordIndex {
        private final List<Chunk> indexed = new ArrayList<>();

        @Override
        public List<RetrievalResult> search(String query, int topK, Map<String, Object> filters) {
            return indexed.stream()
                .filter(c -> matchesFilters(c, filters))
                .limit(topK)
                .map(c -> new RetrievalResult(c, 1.0))
                .toList();
        }

        @Override
        public void index(List<Chunk> chunks) {
            indexed.addAll(chunks);
        }

        @Override
        public void remove(Set<String> chunkIds) {
            indexed.removeIf(c -> chunkIds.contains(c.id()));
        }

        int indexedCount() {
            return indexed.size();
        }

        private boolean matchesFilters(Chunk chunk, Map<String, Object> filters) {
            for (Map.Entry<String, Object> f : filters.entrySet()) {
                Object val = chunk.metadata().get(f.getKey());
                if (!Objects.equals(val, f.getValue())) return false;
            }
            return true;
        }
    }

    static class FakeChunkingStrategy implements ChunkingStrategy {
        private final int chunksPerDoc;

        FakeChunkingStrategy(int chunksPerDoc) {
            this.chunksPerDoc = chunksPerDoc;
        }

        @Override
        public List<Chunk> chunk(Document document) {
            List<Chunk> chunks = new ArrayList<>();
            String[] words = document.content().split(" ");
            int wordsPerChunk = Math.max(1, words.length / chunksPerDoc);
            for (int i = 0; i < chunksPerDoc; i++) {
                int start = i * wordsPerChunk;
                int end = (i == chunksPerDoc - 1) ? words.length : (i + 1) * wordsPerChunk;
                String text = String.join(" ", Arrays.copyOfRange(words, start, end));
                chunks.add(new Chunk(
                    document.id() + "-c" + i,
                    document.id(),
                    text,
                    i,
                    text.length(),
                    null,
                    new HashMap<>(document.metadata())
                ));
            }
            return chunks;
        }

        @Override
        public String name() {
            return "fake";
        }
    }

    static class FakeLlmClient implements LlmClient {
        private final Queue<ChatResponse> responses = new ConcurrentLinkedQueue<>();

        void enqueue(ChatResponse response) {
            responses.add(response);
        }

        @Override
        public Flow.Publisher<StreamEvent> stream(ChatRequest request, CancellationToken cancellationToken) {
            throw new UnsupportedOperationException();
        }

        @Override
        public ChatResponse complete(ChatRequest request, CancellationToken cancellationToken) {
            ChatResponse response = responses.poll();
            if (response == null) {
                throw new IllegalStateException("No fake response enqueued");
            }
            return response;
        }

        @Override public HealthStatus health() { return HealthStatus.HEALTHY; }
        @Override public String providerName() { return "fake"; }
    }

    private ChatResponse chatResponse(String content) {
        return new ChatResponse(
            "resp-1", "test-model", "fake",
            Message.assistant(content),
            new TokenCount(10, content.length(), "test"),
            Duration.ZERO,
            "stop", null, null, Map.of()
        );
    }
}
