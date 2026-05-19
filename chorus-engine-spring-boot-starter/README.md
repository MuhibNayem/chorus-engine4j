# chorus-engine-spring-boot-starter

Auto-configuration for all 17 Chorus Engine modules via Spring Boot.

## Purpose

The `spring-boot-starter` module provides zero-configuration integration with Spring Boot. Add one dependency to your `build.gradle.kts` and every Chorus Engine bean is auto-wired with sensible defaults. All beans are `@ConditionalOnMissingBean` so you can override any piece.

## What Gets Auto-Wired

| Module | Beans |
|---|---|
| Core | `ObjectMapper`, `HttpClient`, `ExecutorService`, `RetryPolicy`, `CircuitBreaker`, `VectorOperations` |
| LLM | `LlmClient`, `EmbeddingClient`, `ProviderRegistry` |
| RAG | `VectorStore`, `ChunkingStrategy`, `ContextAssembler`, `RAGPipeline`, `RetrievalEngine`, `IncrementalRAGStreamer`, `CorrectiveRagEngine`, `AgenticRagOrchestrator`, `SelfRagEvaluator` |
| Agent | `AgentLoop`, `ToolRegistry` |
| Swarm | `SwarmConfig`, `HandoffOrchestrator` |
| Graph | `StateGraph` (configurable), `GraphCheckpointer` (memory/JDBC/Redis) |
| Harness | `HarnessConfig`, `WorkerPool`, `ProjectMemoryStore`, `SemanticTaskRouter` |
| Guardrails | `PiiRedactionEngine`, `List<Guardrail>`, `TieredGuardrailEngine` |
| Telemetry | `EventBus`, `MetricsCollector`, `CostTracker`, `BudgetEnforcer`, `ProvenanceTracker`, `StructuredLogger`, `OpenTelemetryBridge` |
| Memory | `ShortTermMemory`, `LongTermMemory`, `ContextCompactor`, `HierarchicalMemoryManager` |
| MCP | `McpTransport`, `McpClient`, `McpServer` |
| A2A | `A2aClient` |
| Evals | `EvalRunner`, `ParallelEvalRunner`, `LlmJudgeScorer`, `SemanticSimilarityScorer` |

## Quick Start

```kotlin
// build.gradle.kts
dependencies {
    implementation("com.chorus:chorus-engine-spring-boot-starter:0.1.0")
}
```

```yaml
# application.yml
chorus:
  enabled: true
  llm:
    api-key: ${OPENAI_API_KEY}
    model: gpt-4o
    base-url: https://api.openai.com/v1
  rag:
    enabled: true
    vector-store: in-memory
  agent:
    max-rounds: 10
    timeout-seconds: 120
  swarm:
    enabled: true
```

```java
@Service
public class AgentService {
    private final AgentLoop agentLoop;
    private final RAGPipeline rag;

    public AgentService(AgentLoop agentLoop, RAGPipeline rag) {
        this.agentLoop = agentLoop;
        this.rag = rag;
    }

    public String ask(String question) {
        // agentLoop is fully configured and ready to use
        return agentLoop.run(question, CancellationToken.never())
            .collect(...);
    }
}
```

## Configuration Properties

All properties are under the `chorus` prefix. See `ChorusProperties.java` for the full list. Key groups:

| Prefix | Controls |
|---|---|
| `chorus.llm.*` | Provider, model, API key, timeout, temperature |
| `chorus.rag.*` | Vector store, chunking, retrieval strategy, streaming |
| `chorus.agent.*` | Max rounds, timeout, self-healing, HITL |
| `chorus.swarm.*` | Handoff, consensus, cost routing |
| `chorus.graph.*` | Checkpointer type (memory/jdbc/redis) |
| `chorus.harness.*` | Worker pool size, routing thresholds |
| `chorus.guardrails.*` | PII redaction, tiered engine |
| `chorus.telemetry.*` | Metrics, cost tracking, OpenTelemetry |
| `chorus.memory.*` | Short-term limits, hierarchical memory |
| `chorus.mcp.*` | Transport type, endpoint, command |
| `chorus.a2a.*` | Agent card URL, authentication |
| `chorus.evals.*` | Evaluation dataset path, parallelism |

## Conditional Configuration

Advanced features are **opt-in** via `@ConditionalOnProperty`:

```yaml
chorus:
  graph.enabled: true          # DAG workflows
  harness.enabled: true        # Evaluation harness
  guardrails.enabled: true     # Safety guardrails
  memory.enabled: true         # Persistent memory
  mcp.enabled: true            # MCP integration
  a2a.enabled: true            # A2A integration
  evals.enabled: true          # Evaluation framework
  telemetry.open-telemetry-enabled: true
  rag.corrective-rag-enabled: true
  rag.agentic-rag-enabled: true
  rag.self-rag-enabled: true
  memory.hierarchical-enabled: true
```

## Overriding Beans

Any bean can be overridden by defining your own:

```java
@Bean
@Primary
public LlmClient customLlmClient() {
    return new MyCustomProvider(...);
}
```

## Dependencies

All 17 Chorus Engine modules + Spring Boot Web + Spring Boot Actuator + Jackson.

## Thread Safety

All auto-configured beans are thread-safe and intended to be singletons.
