# chorus-engine-graph

Deterministic DAG execution with channels, checkpointing, and speculative execution.

## Purpose

The `graph` module provides workflow orchestration through a directed acyclic graph (DAG). Unlike the free-form ReAct loop, graphs give you explicit control over execution order, branching, retries, and state persistence. Each node is a pure function; edges define the flow.

## Key APIs

| Class / Interface | Purpose |
|---|---|
| `StateGraph<S>` | Build and execute DAGs. Define nodes with `addNode(name, action)`, edges with `addEdge(from, to)`, and conditional edges with `addConditionalEdges(from, router)`. |
| `GraphNode<S>` | A node in the graph. Receives the current state and returns an updated state. |
| `GraphEvent<S>` | Sealed event stream: `NodeStart`, `NodeEnd`, `EdgeTransition`, `CheckpointSaved`, `GraphEnd`, `GraphError`, `SpeculativeHit`. |
| `Channel<T>` | Inter-node communication primitive. `LastValueChannel` (overwrites), `TopicChannel` (broadcasts to all subscribers). |
| `GraphCheckpointer` | Persistent checkpointing. Implementations: `InMemoryCheckpointer`, `JdbcCheckpointer`, `RedisCheckpointer`. |
| `SpeculativeGraphExecutor` | Execute multiple branches speculatively in parallel, keep the first successful result. |

## Graph Execution Model

```
State S ──▶ Node A ──▶ State S' ──▶ [Router] ──▶ Node B or Node C
                                     (conditional)
```

- Nodes are pure functions: `S → S`
- Channels decouple nodes that don't directly connect
- Checkpoints save state after every node for resumability
- Speculative execution runs multiple paths in parallel

## Usage Example

```java
import com.chorus.engine.graph.state.StateGraph;

StateGraph<Map<String, Object>> graph = StateGraph.<Map<String, Object>>builder()
    .addNode("extract", state -> {
        state.put("entities", extractEntities(state.get("query").toString()));
        return state;
    })
    .addNode("research", state -> {
        state.put("results", search(state.get("entities").toString()));
        return state;
    })
    .addNode("synthesize", state -> {
        state.put("answer", synthesize(state.get("results")));
        return state;
    })
    .addEdge("extract", "research")
    .addEdge("research", "synthesize")
    .build();

Map<String, Object> initial = Map.of("query", "What is quantum computing?");
Map<String, Object> result = graph.invoke(initial, "thread-123");
System.out.println(result.get("answer"));
```

## Checkpointing Example

```java
GraphCheckpointer checkpointer = new JdbcCheckpointer(dataSource, new JsonCheckpointSerializer(mapper));

// Resume from checkpoint
Optional<Map<String, Object>> recovered = checkpointer.load("thread-123");
if (recovered.isPresent()) {
    result = graph.invoke(recovered.get(), "thread-123");
}
```

## Dependencies

- `chorus-engine-core`
- `chorus-engine-agent`

## Thread Safety

`StateGraph` is immutable after `build()`. Each `invoke()` is independent. Nodes should be pure functions — side effects break determinism.
