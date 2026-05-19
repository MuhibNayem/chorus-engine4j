# chorus-engine-java

**Headless multi-agent orchestration runtime for Java Spring Boot**

A Java port of `chorus-engine` built on **Spring Boot 4.x**, **Spring AI**, and **Java 25**. Provides streaming ReAct agents, DAG graph execution, multi-agent swarms, HITL, checkpointing, and adaptive skills.

## Tech Stack

- **Java 25** — Virtual threads, sealed interfaces, pattern matching, records
- **Spring Boot 4.0** — Jakarta EE 11, Micrometer 2, OpenTelemetry
- **Spring AI 1.x** — ChatClient, Advisors, @Tool, MCP, VectorStore
- **Project Reactor** — Flux streaming for agent events

## Modules

| Module | Description |
|--------|-------------|
| `chorus-core` | Agent loop, events, HITL, middleware, retry |
| `chorus-checkpoint` | JsonFile, Jdbc, Redis, Durable checkpointers |
| `chorus-graph` | **StateGraph builder + CompiledGraph runtime (from scratch)** |
| `chorus-swarm` | Multi-agent orchestration (handoff, DAG, supervisor, group chat) |
| `chorus-tools` | Filesystem, shell, git, web search with safety |
| `chorus-harness` | Semantic task router, worker engine |
| `chorus-guardrails` | Tiered guardrails (fast/ML/LLM), NER, redaction |
| `chorus-telemetry` | OTLP exporter, in-process tracer |
| `chorus-spring-boot-starter` | Auto-configuration |

## Quick Start

```java
@SpringBootApplication
public class DemoApplication {
    public static void main(String[] args) {
        SpringApplication.run(DemoApplication.class, args);
    }

    @Bean
    CommandLineRunner run(ChatModel chatModel, Checkpointer checkpointer) {
        return args -> {
            LoopOptions options = new LoopOptions(
                chatModel,
                "gpt-4o-mini",
                List.of(new FilesystemTools(), new ShellTool()),
                List.of(ChatMessage.user("List the files in /tmp")),
                "You are a helpful assistant.",
                "thread-1",
                new HitlGate(),
                ApprovalPolicy.AUTO_EDIT,
                checkpointer,
                500,
                Optional.empty(),
                List.of(),
                null,
                120_000,
                null
            );

            AgentLoop loop = new AgentLoop(options);
            loop.run().subscribe(event -> {
                switch (event) {
                    case AgentEvent.TokenEvent e -> System.out.print(e.text());
                    case AgentEvent.ToolStartEvent e -> System.out.println("[Tool: " + e.name() + "]");
                    case AgentEvent.DoneEvent e -> System.out.println("\nDone! Cost: $" + e.costUsd());
                    default -> {}
                }
            });
        };
    }
}
```

## StateGraph Example

```java
StateGraph<Map<String, Object>> graph = new StateGraph<>(Map.of(
    "messages", Channel.<String>append(),
    "answer", Channel.lastValue("")
));

graph.addNode("agent", state -> {
    // Call LLM...
    Map<String, Object> result = new LinkedHashMap<>(state);
    result.put("answer", "42");
    return result;
});
graph.addNode("tools", state -> {
    // Execute tools...
    return state;
});
graph.addEdge(StateGraph.START, "agent");
graph.addConditionalEdge("agent", state ->
    state.containsKey("toolCalls") ? "tools" : StateGraph.END
);
graph.addEdge("tools", "agent");

CompiledGraph<Map<String, Object>> app = graph.compile();
Map<String, Object> result = app.invoke(Map.of());
```

## Graph REST Server

```java
@Bean
GraphRestServer<Map<String, Object>> restServer(CompiledGraph<Map<String, Object>> graph,
                                                   Checkpointer checkpointer) {
    return new GraphRestServer<>(graph, checkpointer, null);
}
```

Endpoints:
- `POST /threads` — Create thread
- `GET /threads/:id` — Get thread
- `POST /threads/:id/runs` — Start run (SSE streaming)
- `POST /threads/:id/runs/:runId/resume` — Resume after interrupt
- `GET /threads/:id/checkpoints` — List checkpoints

## Swarm Orchestration

```java
SwarmConfig config = new SwarmConfig(
    "handoff",
    List.of(
        new SwarmConfig.SwarmAgent("researcher", "You research...", List.of(), List.of("writer"), List.of(), "shared", Optional.empty()),
        new SwarmConfig.SwarmAgent("writer", "You write...", List.of(), List.of(), List.of(), "shared", Optional.empty())
    ),
    "researcher",
    Optional.of(new SwarmConfig.CostBudget(2.50)),
    Optional.empty(),
    500
);

SwarmOrchestrator orchestrator = new SwarmOrchestrator(chatModel, "gpt-4o");
orchestrator.runSwarm(config, "Write a report on AI", "swarm-1", hitlGate, checkpointer)
    .subscribe(event -> System.out.println(event));
```

## Guardrails

```java
TieredGuardrailEngine engine = new TieredGuardrailEngine(
    Duration.ofMillis(500), "critical", true
);
engine.addFastCheck("no-pii", "critical", ctx -> ctx.toString().contains("SSN"));
engine.addMlCheck("topic-drift", "warning", ctx -> false);

GuardrailResult result = engine.run("user input here").join();
if (result.halted()) {
    // Block execution
}
```

## Checkpointing

```java
// File-based (dev)
Checkpointer cp = new JsonFileCheckpointer();

// Durable with event sourcing
Checkpointer cp = new DurableCheckpointer(DurableCheckpointer.DurabilityMode.SYNC);

// JDBC (production)
Checkpointer cp = new JdbcCheckpointer(dataSource);
```

## Features Implemented

- [x] Streaming ReAct agent loop with 30+ event types
- [x] Sealed interface event hierarchy (exhaustive pattern matching)
- [x] HITL gate with timeout, session approvals, disposal
- [x] Middleware system with priority-based parallel execution
- [x] JsonFile + Durable checkpointer (sync/async/exit modes)
- [x] StateGraph builder with compile-time cycle detection
- [x] CompiledGraph runtime with parallel wave execution (virtual threads)
- [x] Channel reducers: lastValue, append, prepend, sum, setUnion, mapMerge, binaryOperator
- [x] GraphInterrupt + Command resume
- [x] State fingerprinting for infinite loop detection
- [x] Deadlock detection
- [x] GraphRestServer (LangGraph Platform-compatible)
- [x] Multi-agent swarm orchestrator (handoff model)
- [x] Circuit breaker + cost budget
- [x] Built-in tools: filesystem (sandboxed), shell (allow-list), git
- [x] Tiered guardrails with latency budgeting
- [x] Spring Boot auto-configuration starter

## Coming Soon

- [ ] Graph swarm with DAG wave execution
- [ ] Supervisor + group chat swarm models
- [ ] Semantic task router with local embeddings
- [ ] Adaptive Skill Runtime (trajectory synthesis)
- [ ] A2A protocol server/client
- [ ] PostgresSaver with JSONB
- [ ] VectorStore integration for memory

## Requirements

- Java 25+
- Spring Boot 4.0+
- Spring AI 1.0+

## License

MIT
