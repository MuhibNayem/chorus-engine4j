# Chorus Engine User Guide

> **Version:** 0.1.0-SNAPSHOT  
> **Java:** 25 (with `--enable-preview`)  
> **Last Updated:** 2026-05-19

---

## Table of Contents

1. [Introduction](#1-introduction)
2. [Getting Started](#2-getting-started)
3. [Core Concepts](#3-core-concepts)
4. [Building an Agent](#4-building-an-agent)
5. [Graph Workflows](#5-graph-workflows)
6. [Multi-Agent Swarms](#6-multi-agent-swarms)
7. [Retrieval-Augmented Generation](#7-retrieval-augmented-generation)
8. [Memory](#8-memory)
9. [Guardrails](#9-guardrails)
10. [Telemetry & Observability](#10-telemetry--observability)
11. [MCP & A2A](#11-mcp--a2a)
12. [Spring Boot Integration](#12-spring-boot-integration)
13. [Production Checklist](#13-production-checklist)
14. [Configuration Reference](#14-configuration-reference)

---

## 1. Introduction

Chorus Engine is a Java-native framework for building production-grade agentic AI systems. It provides:

- **Agent Loop**: ReAct-style reasoning with parallel tool execution
- **Graph Workflows**: Deterministic DAG execution with checkpointing
- **Multi-Agent Swarms**: Handoff, consensus, supervisor, and planner-executor patterns
- **Advanced RAG**: Hybrid retrieval, self-RAG, corrective RAG, agentic RAG, incremental streaming
- **Memory**: Short-term, long-term, episodic, and procedural memory layers
- **Guardrails**: Tiered safety with PII redaction and LLM-based judgment
- **Observability**: Event-driven telemetry with OpenTelemetry integration
- **Protocols**: MCP (Model Context Protocol) and A2A (Agent-to-Agent) support
- **Spring Boot**: Auto-configuration for zero-setup integration

### Why Java?

Chorus Engine is intentionally Java-only. The JVM offers:

- **Type safety** with records and sealed types
- **Virtual threads** for massive concurrency without callback hell
- **GraalVM native images** for sub-100ms cold starts
- **Enterprise integration** with Spring Boot, existing microservices, and monitoring stacks
- **No Python dependency hell** — single JAR deployment

### Architecture Overview

```
┌─────────────────────────────────────────────────────────────┐
│                    Application Layer                         │
│         (Spring Boot, CLI, or embedded)                      │
└─────────────────────────────────────────────────────────────┘
                              │
        ┌─────────────────────┼─────────────────────┐
        ▼                     ▼                     ▼
┌──────────────┐    ┌──────────────┐    ┌──────────────┐
│   Agent      │    │    Graph     │    │   Swarm      │
│   (ReAct)    │    │   (DAG)      │    │ (Multi-Agent)│
└──────────────┘    └──────────────┘    └──────────────┘
        │                     │                     │
        └─────────────────────┼─────────────────────┘
                              ▼
┌─────────────────────────────────────────────────────────────┐
│              Shared Services Layer                           │
│  LLM Client │ RAG │ Memory │ Tools │ Guardrails │ Telemetry │
└─────────────────────────────────────────────────────────────┘
                              │
                              ▼
┌─────────────────────────────────────────────────────────────┐
│              Protocol Layer                                  │
│              MCP Client/Server │ A2A Client/Server           │
└─────────────────────────────────────────────────────────────┘
```

---

## 2. Getting Started

### 2.1 Prerequisites

- Java 25 (OpenJDK or Oracle JDK)
- Gradle 9.1.0+
- Optional: Docker (for vector databases)

### 2.2 Minimal Setup

```kotlin
// build.gradle.kts
dependencies {
    implementation("com.chorus:chorus-engine-core:0.1.0")
    implementation("com.chorus:chorus-engine-llm:0.1.0")
    implementation("com.chorus:chorus-engine-agent:0.1.0")
    implementation("com.chorus:chorus-engine-tools:0.1.0")
}
```

### 2.3 Hello, Agent

```java
import com.chorus.engine.llm.*;
import com.chorus.engine.llm.provider.ProviderRegistry;
import com.chorus.engine.agent.loop.AgentLoop;
import com.chorus.engine.agent.loop.AgentLoopConfig;
import com.chorus.engine.tools.ToolRegistry;
import com.chorus.engine.core.context.Message;
import java.net.http.HttpClient;
import com.fasterxml.jackson.databind.ObjectMapper;

public class HelloAgent {
    public static void main(String[] args) {
        // Infrastructure
        HttpClient httpClient = HttpClient.newHttpClient();
        ObjectMapper mapper = new ObjectMapper();

        // LLM client
        ProviderRegistry registry = ProviderRegistry.defaults(httpClient, mapper);
        LlmClient llm = registry.get("openai");

        // Tools
        ToolRegistry tools = new ToolRegistry();
        tools.register(new CalculatorTool());

        // Agent loop
        AgentLoop agent = new AgentLoop(
            llm,
            tools,
            new InMemoryEventBus(),
            AgentLoopConfig.builder().maxRounds(5).build()
        );

        // Run
        agent.run("What is 2 + 2?", CancellationToken.never())
            .subscribe(new Flow.Subscriber<>() {
                public void onNext(AgentEvent event) {
                    if (event instanceof AgentEvent.StreamToken t) {
                        System.out.print(t.token());
                    }
                }
                public void onError(Throwable t) { t.printStackTrace(); }
                public void onComplete() { System.out.println("\nDone!"); }
                public void onSubscribe(Flow.Subscription s) { s.request(Long.MAX_VALUE); }
            });
    }
}
```

### 2.4 With Spring Boot (Recommended)

Add the starter and configure in `application.yml`:

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
```

```java
@Service
public class MyService {
    private final AgentLoop agentLoop;
    private final RAGPipeline rag;

    public MyService(AgentLoop agentLoop, RAGPipeline rag) {
        this.agentLoop = agentLoop;
        this.rag = rag;
    }
}
```

---

## 3. Core Concepts

### 3.1 AgentEvent

Every significant moment in an agent's lifecycle is an `AgentEvent`. The full hierarchy includes:

| Event | When Fired |
|---|---|
| `StreamToken` | LLM emits a content token |
| `ThinkingStart` / `ThinkingEnd` | Reasoning model begins/ends thinking |
| `ToolCallStart` | Agent decides to call a tool |
| `ToolCallDone` | Tool execution completed |
| `ToolCallError` | Tool execution failed |
| `RoundStart` / `RoundEnd` | New reasoning round begins/ends |
| `HitlRequested` | Human approval needed |
| `HitlResolved` | Human approved or rejected |
| `CheckpointSaved` / `CheckpointLoaded` | State persisted/restored |
| `GuardrailTriggered` | Safety guardrail activated |
| `MemoryRecall` / `MemoryStore` | Memory operation |
| `Handoff` | Control transferred to another agent |
| `StreamStart` / `StreamEnd` | Streaming session begins/ends |
| `Done` | Agent completed successfully |
| `Error` | Fatal error occurred |

Events are immutable records. Replay the event log to reconstruct any state.

### 3.2 Result<T, E>

Railway-oriented programming for error handling:

```java
Result<String, String> parsed = Result.ok("42");
Result<Integer, String> number = parsed
    .map(Integer::parseInt)
    .filter(n -> n > 0, () -> "Must be positive");

int value = number.unwrapOrElse(e -> {
    System.out.println("Error: " + e);
    return 0;
});
```

Rules:
- `Result.ok(null)` throws NPE. Use `new Result.Ok<>(null)` for `Result<Void, E>`.
- All operations are chainable and lazy.

### 3.3 Message

Immutable chat message:

```java
Message system = Message.system("You are a coding assistant.");
Message user = Message.user("Write a sort function.");
Message assistant = Message.assistant("Here's a quicksort...");
Message toolResult = Message.tool("tool-123", "{\"result\": [1, 2, 3]}");
```

### 3.4 CancellationToken

Cooperative cancellation:

```java
CancellationToken token = CancellationToken.withTimeout(Duration.ofMinutes(5));

// In a long-running loop:
if (token.isCancelled()) {
    throw new CancellationException(token.reason());
}
```

### 3.5 FlowCollector

Bridge JDK `Flow.Publisher` to synchronous APIs:

```java
List<StreamEvent> events = FlowCollector.toList(publisher, Duration.ofSeconds(30), token);
Optional<StreamEvent> last = FlowCollector.last(publisher, Duration.ofSeconds(30), token);
```

---

## 4. Building an Agent

### 4.1 The ReAct Loop

The `AgentLoop` implements Reasoning + Action:

1. User sends a message
2. LLM generates reasoning + tool call(s)
3. Tools execute in parallel (virtual threads)
4. Results observed by LLM
5. Repeat until answer or max rounds

```java
AgentLoopConfig config = AgentLoopConfig.builder()
    .maxRounds(10)
    .timeout(Duration.ofMinutes(2))
    .selfHealingEnabled(true)
    .build();

AgentLoop loop = new AgentLoop(llmClient, toolRegistry, eventBus, config);
```

### 4.2 Writing Tools

Implement the `Tool` interface:

```java
public class WeatherTool implements Tool {
    @Override public String name() { return "weather"; }
    @Override public String description() { return "Get weather for a city"; }

    @Override public Map<String, Object> parametersSchema() {
        return Map.of(
            "type", "object",
            "properties", Map.of(
                "city", Map.of("type", "string", "description", "City name")
            ),
            "required", List.of("city")
        );
    }

    @Override public Object execute(Map<String, Object> args) {
        String city = args.get("city").toString();
        return fetchWeather(city);
    }
}
```

### 4.3 Human-in-the-Loop

Pause before sensitive operations:

```java
HitlGate gate = HitlGate.builder()
    .sensitiveTools(Set.of("shell", "filesystem_delete", "database_write"))
    .timeout(Duration.ofSeconds(30))
    .build();

// In your approval UI:
gate.approve("gate-id-123", "Approved by admin");
// Or reject:
gate.reject("gate-id-123", "Too risky");
// Or approve for entire session:
gate.approveSession("gate-id-123");
```

### 4.4 Middleware

Intercept and modify behavior:

```java
public class AuditMiddleware implements AgentLoopMiddleware {
    @Override public void beforeTool(String toolName, Map<String, Object> args, String runId) {
        auditLog.info("[{}] Tool: {} args: {}", runId, toolName, args);
    }

    @Override public void afterRound(AgentState state, String runId) {
        auditLog.info("[{}] Round {} complete", runId, state.roundIndex());
    }
}

AgentLoop loop = new AgentLoop(llm, tools, eventBus, config);
loop.addMiddleware(new AuditMiddleware());
```

### 4.5 Self-Healing

Enable automatic recovery from transient failures:

```java
AgentLoopConfig config = AgentLoopConfig.builder()
    .selfHealingEnabled(true)
    .selfHealingMaxRetries(3)
    .build();
```

When the model produces malformed tool calls or gets stuck, the loop:
1. Retries with exponential backoff
2. Falls back to simpler prompts
3. Escalates to HITL if all retries fail

---

## 5. Graph Workflows

### 5.1 Building a DAG

Graphs provide deterministic execution order:

```java
StateGraph<Map<String, Object>> graph = StateGraph.<Map<String, Object>>builder()
    .addNode("extract", state -> {
        state.put("entities", nerService.extract(state.get("query").toString()));
        return state;
    })
    .addNode("research", state -> {
        state.put("results", searchService.search(state.get("entities").toString()));
        return state;
    })
    .addNode("synthesize", state -> {
        state.put("answer", llmClient.synthesize(state.get("results")));
        return state;
    })
    .addEdge("extract", "research")
    .addEdge("research", "synthesize")
    .build();

Map<String, Object> result = graph.invoke(
    Map.of("query", "What is quantum computing?"),
    "thread-123"
);
```

### 5.2 Conditional Edges

Route based on state:

```java
graph.addConditionalEdges("classify", state -> {
    String intent = state.get("intent").toString();
    return switch (intent) {
        case "question" -> "answer";
        case "action" -> "execute";
        default -> "clarify";
    };
});
```

### 5.3 Channels

Decouple nodes that don't directly connect:

```java
Channel<String> topic = new TopicChannel<>();

// Node A publishes
topic.publish("update", "New data available");

// Nodes B and C subscribe
topic.subscribe("update", message -> { /* ... */ });
```

### 5.4 Checkpointing

Resume from any point:

```java
GraphCheckpointer checkpointer = new JdbcCheckpointer(dataSource, serializer);

// Execute with checkpointing
Map<String, Object> result = graph.invoke(initialState, "thread-123", checkpointer);

// Later, resume from checkpoint
Optional<Map<String, Object>> recovered = checkpointer.load("thread-123");
```

### 5.5 Speculative Execution

Run multiple branches in parallel, keep the first success:

```java
SpeculativeGraphExecutor executor = new SpeculativeGraphExecutor(graph);
Map<String, Object> result = executor.invokeSpeculative(
    initialState,
    List.of("path_a", "path_b", "path_c"),
    "thread-123"
);
```

---

## 6. Multi-Agent Swarms

### 6.1 Handoff Pattern

Agents dynamically transfer control:

```java
AgentDefinition researcher = AgentDefinition.builder()
    .name("researcher")
    .systemPrompt("Find facts and cite sources.")
    .model("gpt-4o")
    .tools(Set.of("web_search"))
    .build();

AgentDefinition writer = AgentDefinition.builder()
    .name("writer")
    .systemPrompt("Synthesize research into prose.")
    .model("gpt-4o")
    .build();

HandoffOrchestrator orchestrator = new HandoffOrchestrator(
    List.of(researcher, writer),
    llmClient,
    toolRegistry
);

orchestrator.run("Write a report on fusion energy", token)
    .subscribe(event -> {
        if (event instanceof SwarmEvent.HandoffOccurred h) {
            System.out.println(h.fromAgent() + " → " + h.toAgent());
        }
    });
```

### 6.2 Consensus Pattern

Multiple agents vote:

```java
AgentConsensusEngine consensus = new AgentConsensusEngine(
    List.of(researcher, analyst, critic),
    llmClient,
    ConsensusConfig.builder()
        .minAgreementRatio(0.66)
        .maxRounds(3)
        .build()
);

ConsensusResult result = consensus.decide("Is this code secure?", token);
System.out.println("Consensus: " + result.answer());
System.out.println("Confidence: " + result.confidence());
```

### 6.3 Supervisor Pattern

Hierarchical delegation:

```java
SupervisorOrchestrator supervisor = new SupervisorOrchestrator(
    supervisorAgent,
    List.of(worker1, worker2, worker3),
    llmClient
);

// Workers execute in parallel via StructuredTaskScope
supervisor.run("Build a microservice", token);
```

### 6.4 Planner-Executor

Task decomposition:

```java
PlannerExecutorOrchestrator orchestrator = new PlannerExecutorOrchestrator(
    plannerAgent,
    List.of(coder, tester, reviewer),
    llmClient
);

// Planner breaks "Build auth service" into:
// 1. Design schema → 2. Implement endpoints → 3. Write tests → 4. Code review
// Executors run each step, passing artifacts forward
orchestrator.run("Build an authentication microservice", token);
```

### 6.5 Cost-Aware Routing

Track and limit token spend across the swarm:

```java
SwarmConfig config = SwarmConfig.builder()
    .maxTotalCost(BigDecimal.valueOf(2.00), Currency.USD)
    .costRoutingEnabled(true)
    .build();

// When budget approaches limit, expensive agents fall back to cheaper models
// When budget exceeded, operation halts with BudgetExceededEvent
```


---

## 7. Retrieval-Augmented Generation

### 7.1 Standard RAG Pipeline

```java
RAGPipeline rag = RAGPipeline.builder()
    .vectorStore(new InMemoryVectorStore(embeddingClient))
    .chunkingStrategy(new FixedSizeChunking(500, 50))
    .retrievalEngine(new HybridRetrievalEngine(vectorStore, embeddingClient))
    .contextAssembler(new ContextAssembler(tokenizer, 3000))
    .llmClient(llmClient)
    .build();

// Index documents
rag.index(List.of(
    Document.from("Quantum computing uses qubits instead of classical bits..."),
    Document.from("Shor's algorithm can factor large integers efficiently...")
));

// Query
RAGResponse response = rag.query("How do quantum computers break encryption?");
System.out.println(response.answer());
response.sources().forEach(s -> System.out.println("  [" + s.score() + "] " + s.content().substring(0, 100)));
```

### 7.2 Hybrid Retrieval

Combines dense vector similarity with keyword (BM25) search:

```java
HybridRetrievalEngine engine = new HybridRetrievalEngine(
    vectorStore,           // Dense: semantic meaning
    keywordIndex,          // Sparse: exact word matches
    embeddingClient,
    HybridConfig.builder()
        .denseWeight(0.7)
        .keywordWeight(0.3)
        .topK(10)
        .build()
);
```

The two result sets are fused using reciprocal rank fusion (RRF) and reranked by an LLM judge.

### 7.3 Self-RAG

Evaluate and re-retrieve automatically:

```java
RAGPipeline rag = RAGPipeline.builder()
    // ... standard config
    .selfRagEvaluator(new SelfRagEvaluator(llmClient))
    .maxRetrievalRounds(3)
    .build();

// The pipeline will:
// 1. Retrieve initial context
// 2. Generate a draft answer
// 3. Ask the evaluator: "Is this answer fully supported by the context?"
// 4. If NO: re-retrieve with refined query, goto 2
// 5. If YES: return final answer
```

### 7.4 Corrective RAG

Detect and fix hallucinations:

```java
RAGPipeline rag = RAGPipeline.builder()
    // ... standard config
    .correctiveRagEnabled(true)
    .postGenerationVerifier(new PostGenerationVerifier(llmClient))
    .build();

// After generation, the verifier checks each claim against sources.
// Unsupported claims trigger re-retrieval and regeneration.
```

### 7.5 Agentic RAG

Let an agent decide the retrieval strategy:

```java
RAGPipeline rag = RAGPipeline.builder()
    // ... standard config
    .agenticRagEnabled(true)
    .agenticOrchestrator(new AgenticRagOrchestrator(agentLoop))
    .build();

// The agent can:
// - Decide whether retrieval is needed at all
// - Choose between vector, keyword, or hybrid search
// - Reformulate queries multiple times
// - Synthesize across multiple retrieval rounds
```

### 7.6 Incremental Streaming

Stream tokens while asynchronously retrieving additional context:

```java
IncrementalRAGStreamer streamer = new IncrementalRAGStreamer(
    ragPipeline,
    llmClient,
    StreamingConfig.builder()
        .maxWaves(3)
        .waveDelay(Duration.ofMillis(500))
        .build()
);

streamer.stream("Explain transformer architectures", token)
    .subscribe(new Flow.Subscriber<>() {
        public void onNext(StreamEvent event) {
            switch (event) {
                case StreamEvent.Token t -> System.out.print(t.token());
                case SupplementalContext s -> System.out.println("\n[Additional context retrieved]");
            }
        }
    });
```

**Key principle:** Once generation starts, it is never cancelled. Late-arriving context becomes supplemental, verified post-generation.

### 7.7 Vector Stores

| Store | Type | Best For |
|---|---|---|
| `InMemoryVectorStore` | Heap | Testing, small datasets |
| `PgVectorStore` | PostgreSQL + pgvector | Production with existing Postgres |
| `PineconeVectorStore` | Cloud managed | Serverless, auto-scaling |
| `QdrantVectorStore` | Self-hosted/cloud | High-performance filtering |
| `MilvusVectorStore` | Distributed | Large-scale enterprise |

---

## 8. Memory

### 8.1 Four Memory Types

| Type | Scope | Persistence | Use Case |
|---|---|---|---|
| **Short-Term** | Single conversation | Session | Recent messages, sliding window |
| **Long-Term** | Cross-conversation | Persistent | User preferences, learned facts |
| **Episodic** | Cross-conversation | Persistent | Significant events, successes/failures |
| **Procedural** | Cross-conversation | Persistent | Reusable workflows, tool sequences |

### 8.2 Using HierarchicalMemoryManager

```java
HierarchicalMemoryManager memory = HierarchicalMemoryManager.builder()
    .shortTerm(ShortTermMemory.builder().maxTokens(4000).build())
    .longTerm(LongTermMemory.builder().store(new InMemoryFactStore()).build())
    .episodic(EpisodicMemory.builder().embeddingClient(embeddingClient).build())
    .procedural(ProceduralMemory.builder().build())
    .compactor(new ContextCompactor(llmClient))
    .build();

// Before agent run, recall relevant context
List<String> context = memory.recall("Generate a REST API for user management");
// Returns: ["user.preference.format=json", "user.framework=spring-boot", ...]

// Store a preference
memory.longTerm().store("user.preference.format", "json");

// Store a significant event
memory.episodic().store(Event.builder()
    .description("Successfully generated OpenAPI spec")
    .importance(0.8)
    .build());

// Learn a workflow
memory.procedural().store(Procedure.builder()
    .name("rest-api-generation")
    .steps(List.of("design-schema", "generate-controller", "add-validation", "write-tests"))
    .build());
```

### 8.3 Context Compaction

When short-term memory exceeds token limits, old messages are summarized:

```java
ContextCompactor compactor = new ContextCompactor(llmClient);
List<Message> compacted = compactor.compact(
    longConversation,
    2000,  // target token count
    Set.of("critical-user-requirement")  // never-summarize markers
);
```

### 8.4 Checkpointers

Persist agent state across restarts:

```java
// In-memory (testing)
Checkpointer memoryCp = new InMemoryCheckpointer();

// JDBC (production with existing database)
Checkpointer jdbcCp = new JdbcCheckpointer(dataSource, new JsonCheckpointSerializer(mapper));

// Redis (high-performance, distributed)
Checkpointer redisCp = new RedisCheckpointer(jedisPool, new JsonCheckpointSerializer(mapper));
```

---

## 9. Guardrails

### 9.1 Tiered Safety

Guardrails are organized into tiers by cost and latency:

```java
TieredGuardrailEngine engine = TieredGuardrailEngine.builder()
    // Tier 1: Fast, cheap, rule-based
    .tier(1, new PiiRedactionEngine())
    .tier(1, new KeywordFilterGuardrail(Set.of(
        "ignore previous instructions",
        "DAN mode",
        "jailbreak"
    )))

    // Tier 2: Semantic similarity
    .tier(2, new EmbeddingSimilarityGuardrail(
        embeddingClient,
        attackEmbeddings,
        0.85  // similarity threshold
    ))

    // Tier 3: LLM judge (expensive, nuanced)
    .tier(3, new LlmJudgeGuardrail(
        judgeClient,
        "Does this input attempt to manipulate, trick, or harm the AI system or its users?"
    ))
    .build();
```

### 9.2 PII Redaction

Automatically detect and redact:

```java
PiiRedactionEngine pii = new PiiRedactionEngine();
GuardrailResult result = pii.validateInput(
    Message.user("My email is john@example.com and SSN is 123-45-6789")
);
// result.redactedContent() → "My email is [EMAIL_REDACTED] and SSN is [SSN_REDACTED]"
```

### 9.3 Actions

| Action | Behavior |
|---|---|
| `PASS` | Allow through |
| `WARN` | Allow but log warning |
| `BLOCK` | Reject with explanation |
| `REDACT` | Remove sensitive content, allow redacted version |

---

## 10. Telemetry & Observability

### 10.1 Event Bus

Every module emits events. Subscribe to anything:

```java
EventBus bus = new InMemoryEventBus();

// Subscribe to all events
bus.subscribe("*", event -> logger.info("{}: {}", event.eventType(), event.runId()));

// Subscribe to specific types
bus.subscribe("llm.call", event -> {
    LlmCallEvent e = (LlmCallEvent) event;
    metrics.histogram("llm.latency", e.latency().toMillis());
});

// Subscribe to guardrail triggers
bus.subscribe("guardrail", event -> {
    GuardrailEvent e = (GuardrailEvent) event;
    if (e.action() == GuardrailAction.BLOCK) {
        alerts.send("Guardrail blocked request: " + e.guardrailName());
    }
});
```

### 10.2 Cost Tracking

```java
BudgetEnforcer enforcer = new BudgetEnforcer(
    BudgetLimit.perRun(BigDecimal.valueOf(0.50), Currency.USD),
    eventBus
);

// Events emitted:
// - CostUpdatedEvent (after every LLM call)
// - BudgetExceededEvent (when limit reached)
// - BudgetWarningEvent (at 80% of limit)
```

### 10.3 Provenance

Immutable audit trail of every decision:

```java
ProvenanceTracker tracker = new ProvenanceTracker(eventBus);

// After a run, query the audit trail
ProvenanceRecord record = tracker.getRecord("run-123");
record.decisions().forEach(d -> {
    System.out.println(d.timestamp() + " " + d.agent() + " → " + d.action());
});
```

### 10.4 OpenTelemetry

```java
// Requires opentelemetry-api on classpath
OpenTelemetryBridge bridge = new OpenTelemetryBridge(eventBus, OtelConfig.builder()
    .endpoint("http://localhost:4317")
    .samplingRate(1.0)
    .build());

// Auto-instrumentation:
// - Agent runs → Traces
// - Tool calls → Spans
// - LLM calls → Spans + Metrics
// - RAG queries → Spans
```

---

## 11. MCP & A2A

### 11.1 MCP Client

Connect to external tool servers:

```java
McpTransport transport = new HttpSseTransport(
    URI.create("http://localhost:3000/sse"), httpClient, mapper);

McpClient client = new McpClient(transport);
List<ToolDefinition> tools = client.listTools();
McpResult.CallToolResult result = client.callTool("weather", Map.of("city", "Tokyo"));
```

### 11.2 MCP Server

Expose Chorus tools to external clients:

```java
McpServer server = new McpServer(
    transport,
    ServerCapabilities.builder().tools(true).resources(true).build(),
    mapper
);
server.registerTool(new CalculatorTool());
server.start();
```

### 11.3 A2A Client

Discover and call other agents:

```java
A2aClient client = new A2aClient();
AgentCard card = client.fetchAgentCard(
    URI.create("https://agents.example.com/research/.well-known/agent.json"));

Task task = client.sendTask(card.url(), "Research quantum computing");
while (!task.status().isTerminal()) {
    Thread.sleep(1000);
    task = client.getTask(card.url(), task.id());
}
```

---

## 12. Spring Boot Integration

### 12.1 Auto-Configuration

Add the starter dependency and every bean is auto-wired:

```yaml
chorus:
  enabled: true
  llm:
    api-key: ${OPENAI_API_KEY}
    model: gpt-4o
    temperature: 0.7
  rag:
    enabled: true
    vector-store: in-memory
    chunk-size: 500
    chunk-overlap: 50
  agent:
    max-rounds: 10
    timeout-seconds: 120
    self-healing-enabled: true
  swarm:
    enabled: true
    max-agents: 5
  memory:
    enabled: true
    short-term-max-tokens: 4000
  guardrails:
    enabled: true
    pii-redaction: true
  telemetry:
    enabled: true
    cost-tracking: true
    open-telemetry-enabled: false
```

### 12.2 Overriding Beans

```java
@Bean
@Primary
public LlmClient customLlmClient() {
    return new MyCustomProvider("https://internal-llm.corp.com", apiKey);
}

@Bean
public VectorStore customVectorStore() {
    return new PgVectorStore(dataSource, embeddingClient);
}
```

### 12.3 Conditional Features

All advanced features are opt-in:

```yaml
chorus:
  graph.enabled: true
  harness.enabled: true
  mcp.enabled: true
  a2a.enabled: true
  evals.enabled: true
  rag.corrective-rag-enabled: true
  rag.agentic-rag-enabled: true
  rag.self-rag-enabled: true
  memory.hierarchical-enabled: true
  telemetry.open-telemetry-enabled: true
```

---

## 13. Production Checklist

### 13.1 Security

- [ ] Replace `ShellTool` and `FilesystemTool` with sandboxed alternatives (Docker, gVisor)
- [ ] Enable guardrails (at minimum Tier 1 + Tier 2)
- [ ] Configure HITL for sensitive tools
- [ ] Rotate API keys via environment variables or secret managers
- [ ] Enable PII redaction if handling user data
- [ ] Set budget limits per run and per session

### 13.2 Reliability

- [ ] Configure retry policies with exponential backoff
- [ ] Enable circuit breakers for all LLM providers
- [ ] Use JDBC or Redis checkpointers for durability
- [ ] Set max rounds and timeouts on all agent loops
- [ ] Enable self-healing for transient failures
- [ ] Test fallback behavior when LLM providers are unavailable

### 13.3 Observability

- [ ] Subscribe to event bus for critical events
- [ ] Export metrics to Prometheus/Grafana
- [ ] Enable OpenTelemetry for distributed tracing
- [ ] Track token costs per run, per user, per session
- [ ] Log provenance for audit compliance
- [ ] Set up alerts for budget exceeded and guardrail triggers

### 13.4 Performance

- [ ] Use virtual threads for I/O-bound operations (default)
- [ ] Configure connection pooling for HTTP clients
- [ ] Use connection pooling for JDBC checkpointers
- [ ] Enable GraalVM native image for sub-100ms cold starts
- [ ] Use incremental RAG streaming for perceived latency
- [ ] Cache embeddings for frequently retrieved documents

### 13.5 Deployment

- [ ] Build native image: `./gradlew :sample:nativeCompile`
- [ ] Or deploy as JAR: `./gradlew bootJar`
- [ ] Configure health checks (Spring Boot Actuator)
- [ ] Set JVM heap limits (recommend 2g for typical workloads)
- [ ] Enable `--enable-preview` and `--add-modules jdk.incubator.vector`

---

## 14. Configuration Reference

### 14.1 Property Groups

| Prefix | Description |
|---|---|
| `chorus.enabled` | Master toggle (default: `true`) |
| `chorus.llm.*` | LLM provider configuration |
| `chorus.rag.*` | RAG pipeline configuration |
| `chorus.agent.*` | Agent loop configuration |
| `chorus.swarm.*` | Multi-agent swarm configuration |
| `chorus.graph.*` | Graph workflow configuration |
| `chorus.harness.*` | Evaluation harness configuration |
| `chorus.guardrails.*` | Safety guardrails configuration |
| `chorus.telemetry.*` | Observability configuration |
| `chorus.memory.*` | Memory system configuration |
| `chorus.mcp.*` | MCP protocol configuration |
| `chorus.a2a.*` | A2A protocol configuration |
| `chorus.evals.*` | Evaluation framework configuration |

### 14.2 LLM Properties

```yaml
chorus:
  llm:
    api-key: ${OPENAI_API_KEY}        # Required
    base-url: https://api.openai.com/v1
    model: gpt-4o
    temperature: 0.7
    max-tokens: 4096
    connect-timeout-seconds: 30
    read-timeout-seconds: 120
    retry-max-attempts: 3
    retry-base-delay-ms: 1000
    circuit-breaker-failure-threshold: 5
    circuit-breaker-reset-timeout-seconds: 30
```

### 14.3 RAG Properties

```yaml
chorus:
  rag:
    enabled: true
    vector-store: in-memory           # in-memory, pgvector, pinecone, qdrant, milvus
    chunk-size: 500
    chunk-overlap: 50
    top-k: 10
    context-window-tokens: 3000
    hybrid-retrieval: true
    hybrid-dense-weight: 0.7
    hybrid-keyword-weight: 0.3
    incremental-streaming: false
    corrective-rag-enabled: false
    agentic-rag-enabled: false
    self-rag-enabled: false
    self-rag-max-rounds: 3
```

### 14.4 Agent Properties

```yaml
chorus:
  agent:
    max-rounds: 10
    timeout-seconds: 120
    hitl-timeout-seconds: 30
    self-healing-enabled: false
    self-healing-max-retries: 3
    middleware-enabled: false
```

### 14.5 Memory Properties

```yaml
chorus:
  memory:
    enabled: false
    short-term-max-tokens: 4000
    hierarchical-enabled: false
    checkpointing-enabled: false
    checkpointer-type: memory          # memory, jdbc, redis
```

### 14.6 Guardrails Properties

```yaml
chorus:
  guardrails:
    enabled: false
    pii-redaction: true
    prompt-injection-detection: true
    tier-2-similarity-threshold: 0.85
    tier-3-llm-judge: false
```

### 14.7 Telemetry Properties

```yaml
chorus:
  telemetry:
    enabled: true
    metrics-enabled: true
    cost-tracking-enabled: true
    provenance-tracking-enabled: true
    open-telemetry-enabled: false
    open-telemetry-endpoint: http://localhost:4317
    open-telemetry-sampling-rate: 1.0
```

---

## Appendix A: Module Dependency Graph

```
chorus-engine-core
    └── chorus-engine-tokenizer
            └── chorus-engine-llm
                    ├── chorus-engine-agent
                    │       └── chorus-engine-graph
                    │       └── chorus-engine-swarm
                    │               └── chorus-engine-harness
                    ├── chorus-engine-tools
                    ├── chorus-engine-guardrails
                    ├── chorus-engine-skills
                    ├── chorus-engine-mcp
                    ├── chorus-engine-a2a
                    ├── chorus-engine-evals
                    ├── chorus-engine-memory
                    └── chorus-engine-rag
                            └── chorus-engine-spring-boot-starter
```

## Appendix B: Version Compatibility

| Component | Version |
|---|---|
| Java | 25 |
| Gradle | 9.1.0 |
| Spring Boot | 4.0.0 |
| Jackson | 2.18.0 |
| JUnit | 5.11.0 |

## Appendix C: Getting Help

- **Issues:** https://github.com/chorus-engine/chorus-engine/issues
- **Discussions:** https://github.com/chorus-engine/chorus-engine/discussions
- **Documentation:** See `docs/` directory in this repository
- **Module Docs:** See each module's `README.md`
