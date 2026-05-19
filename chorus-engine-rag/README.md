# chorus-engine-rag

Retrieval-Augmented Generation with hybrid retrieval, self-RAG, corrective RAG, and agentic RAG.

## Purpose

The `rag` module provides production-grade RAG pipelines that go far beyond simple vector similarity search. It includes hybrid retrieval (dense + keyword), self-evaluating RAG, corrective RAG, agentic RAG, and incremental streaming.

## Key APIs

| Class | Purpose |
|---|---|
| `RAGPipeline` | Main entry point. Orchestrates retrieval, context assembly, and augmentation. |
| `RetrievalEngine` | Interface for retrieval strategies. |
| `HybridRetrievalEngine` | Combines dense vector search with keyword (BM25) search. Reranks fused results. |
| `VectorStore` | Interface for vector databases. Implementations: `InMemoryVectorStore`, `PgVectorStore`, `PineconeVectorStore`, `QdrantVectorStore`, `MilvusVectorStore`. |
| `ChunkingStrategy` | Interface for document chunking. `FixedSizeChunking` with overlap support. |
| `ContextAssembler` | Assembles retrieved chunks into a context window, respecting token limits and relevance scores. |
| `SelfRagEvaluator` | Evaluates whether retrieved context is sufficient. If not, triggers re-retrieval with refined queries. |
| `CorrectiveRagEngine` | Detects hallucinations in generated output and auto-corrects by re-retrieving missing information. |
| `AgenticRagOrchestrator` | Uses an agent to dynamically decide when to retrieve, what to retrieve, and how to synthesize. |
| `IncrementalRAGStreamer` | Streams LLM tokens while asynchronously retrieving additional context waves. Late-arriving context becomes supplemental, never cancels generation. |
| `LlmJudgeReranker` | Reranks retrieved chunks using an LLM judge for relevance scoring. |

## RAG Patterns

| Pattern | Description | Latency | Quality |
|---|---|---|---|
| **Standard RAG** | Retrieve → Assemble → Generate | Low | Good |
| **Hybrid Retrieval** | Dense + keyword fusion → Rerank | Medium | Better |
| **Self-RAG** | Generate → Evaluate → Re-retrieve if needed | Medium | High |
| **Corrective RAG** | Generate → Detect hallucination → Fix | Medium-High | High |
| **Agentic RAG** | Agent decides retrieval strategy dynamically | High | Highest |
| **Incremental Streaming** | Stream + async late retrieval | Low (perceived) | Good |

## Usage Example

```java
import com.chorus.engine.rag.pipeline.RAGPipeline;
import com.chorus.engine.rag.retrieval.HybridRetrievalEngine;
import com.chorus.engine.rag.store.InMemoryVectorStore;
import com.chorus.engine.rag.chunking.FixedSizeChunking;

// Build pipeline
RAGPipeline rag = RAGPipeline.builder()
    .vectorStore(new InMemoryVectorStore(embeddingClient))
    .chunkingStrategy(new FixedSizeChunking(500, 50))
    .retrievalEngine(new HybridRetrievalEngine(vectorStore, embeddingClient))
    .contextAssembler(new ContextAssembler(tokenizer, 3000))
    .llmClient(llmClient)
    .build();

// Index documents
rag.index(List.of(
    Document.from("Quantum computing uses qubits..."),
    Document.from("Shor's algorithm factors integers...")
));

// Query
RAGResponse response = rag.query("How do quantum computers break encryption?");
System.out.println(response.answer());
response.sources().forEach(s -> System.out.println("  Source: " + s.content()));
```

## Self-RAG Example

```java
SelfRagEvaluator evaluator = new SelfRagEvaluator(llmClient);
RAGPipeline rag = RAGPipeline.builder()
    // ... standard config
    .selfRagEvaluator(evaluator)
    .maxRetrievalRounds(3)
    .build();

// If the evaluator judges context insufficient, the pipeline
// automatically re-queries with a refined question
```

## Dependencies

- `chorus-engine-core`
- `chorus-engine-llm`
- `chorus-engine-memory`
- `chorus-engine-agent`
- Jackson

## Thread Safety

`RAGPipeline` is thread-safe. `VectorStore` implementations are thread-safe. Document indexing and querying can happen concurrently.
