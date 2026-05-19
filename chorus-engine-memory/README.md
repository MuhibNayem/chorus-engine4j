# chorus-engine-memory

Multi-layer memory system: short-term, long-term, episodic, and procedural.

## Purpose

The `memory` module gives agents memory that persists across conversations. It implements four memory types inspired by cognitive science, plus a hierarchical memory manager that orchestrates them.

## Key APIs

| Class / Interface | Purpose |
|---|---|
| `ShortTermMemory` | Recent conversation context. Sliding window of messages with token-aware truncation. |
| `LongTermMemory` | Persistent key-value store for facts, preferences, and learned information. |
| `EpisodicMemory` | Event-based memory. Stores significant events (successes, failures, insights) with embeddings for semantic retrieval. |
| `ProceduralMemory` | Learned skills and workflows. Stores successful tool-call sequences as reusable recipes. |
| `HierarchicalMemoryManager` | Orchestrates all four memory layers. Automatically promotes information from short-term → long-term → episodic based on importance and recency. |
| `ContextCompactor` | Compresses long conversations by summarizing old turns while preserving critical information. |
| `Checkpointer` | Interface for persisting agent state. Implementations: `InMemoryCheckpointer`, `JdbcCheckpointer`, `RedisCheckpointer`. |
| `JsonCheckpointSerializer` | Serializes checkpoints to JSON using Jackson. |

## Memory Hierarchy

```
┌─────────────────────────────────────────────┐
│           Short-Term Memory                 │  ← Recent messages (sliding window)
│    Auto-summarized by ContextCompactor      │
└─────────────────────────────────────────────┘
              ↓ (important facts extracted)
┌─────────────────────────────────────────────┐
│           Long-Term Memory                  │  ← Key-value facts
│    "User prefers JSON output"               │
└─────────────────────────────────────────────┘
              ↓ (significant events)
┌─────────────────────────────────────────────┐
│           Episodic Memory                   │  ← Embeddings of important events
│    "Successfully debugged race condition"   │
└─────────────────────────────────────────────┘
              ↓ (reusable workflows)
┌─────────────────────────────────────────────┐
│           Procedural Memory                 │  ← Learned tool sequences
│    "Debug workflow: logs → grep → analyze"  │
└─────────────────────────────────────────────┘
```

## Usage Example

```java
import com.chorus.engine.memory.*;

// Build memory system
HierarchicalMemoryManager memory = HierarchicalMemoryManager.builder()
    .shortTerm(ShortTermMemory.builder().maxTokens(4000).build())
    .longTerm(LongTermMemory.builder().store(new InMemoryFactStore()).build())
    .episodic(EpisodicMemory.builder().embeddingClient(embeddingClient).build())
    .procedural(ProceduralMemory.builder().build())
    .compactor(new ContextCompactor(llmClient))
    .build();

// Store a fact
memory.longTerm().store("user.preference.format", "json");

// Recall before agent run
List<String> relevantFacts = memory.recall("Generate a REST API");
// Returns: ["user.preference.format=json", ...]

// After successful task, store episode
memory.episodic().store(Event.builder()
    .description("Successfully generated OpenAPI spec for user")
    .importance(0.8)
    .build());
```

## Checkpointers

| Implementation | Persistence | Use Case |
|---|---|---|
| `InMemoryCheckpointer` | Heap only | Testing, single-node deployments |
| `JdbcCheckpointer` | Relational database (PostgreSQL, H2, etc.) | Production with existing DB |
| `RedisCheckpointer` | Redis | High-performance, distributed deployments |

## Dependencies

- `chorus-engine-core`
- `chorus-engine-tokenizer`
- `chorus-engine-llm`
- Jackson
- HikariCP (for JDBC)
- Jedis (for Redis)

## Thread Safety

All memory implementations are thread-safe. `HierarchicalMemoryManager` coordinates access across layers.
