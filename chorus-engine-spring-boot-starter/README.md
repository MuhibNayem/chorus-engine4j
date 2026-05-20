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
    implementation("io.github.muhibnayem:chorus-engine-spring-boot-starter:0.1.0")
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
// 1. Declare your Agent and its Tools
@Agent(name = "assistant", systemPrompt = "You are a helpful assistant.")
@Component
public class MyAgent {

    @Tool(description = "Adds two integers")
    public int add(@ToolParam(description = "First integer") int a, @ToolParam(description = "Second integer") int b) {
        return a + b;
    }
}

// 2. Consume the generated AgentLoop bean
@Service
public class ChatService {
    private final AgentLoop assistantAgentLoop;

    public ChatService(AgentLoop assistantAgentLoop) {
        this.assistantAgentLoop = assistantAgentLoop;
    }

    public Flow.Publisher<AgentEvent> ask(String question) {
        return assistantAgentLoop.run(question, CancellationToken.never());
    }
}
```

## Annotation-Based Scanning & AOT Processing

The starter automatically registers annotation post-processors for compiling and configuring:
*   **`@Agent` & `@Tool`**: Generates reflection metadata and registers specialized `AgentLoop` beans.
*   **`@SwarmAgent` & `@SwarmConfig`**: Automatically aggregates swarm metadata and overrides the default `SwarmOrchestrator` bean.
*   **`@GraphWorkflow` & `@GraphNode`**: Automatically registers state graphs compiled as beans.
*   **`@EventHandler`**: Automatically hooks into the telemetry `EventBus`.
*   **`@Guardrail`**: Dynamically registers safety guardrails in the `TieredGuardrailEngine`.
*   **`@Skill`**: Registers semantic skill blocks in the `SkillRegistry`.

All annotation-processing steps are integrated with **Spring AOT**, automatically creating `reflect-config.json` rules during AOT build execution for **GraalVM Native Image** compatibility.


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
