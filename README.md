# Chorus Engine for Java

> **Enterprise-grade multi-agent orchestration framework for Java 25 + Spring Boot 4.0 + Spring AI 2.0**

## Overview

Chorus Engine Java is a headless, production-ready multi-agent orchestration runtime built on the latest Java and Spring ecosystem. It provides everything needed to build, deploy, and observe LLM-powered agents at scale.

## Technology Stack

| Component | Version | Notes |
|-----------|---------|-------|
| Java | 25 LTS | Scoped Values, Structured Concurrency, Stable Values |
| Spring Boot | 4.0.0 | Virtual threads, declarative HTTP clients, Jackson 3 |
| Spring AI | 2.0.0-M1 | GPT-5-mini default, Advisors API, MCP, structured output |
| Project Reactor | 2024.0.0 | Reactive streaming foundation |
| OpenTelemetry | 1.x | Native tracing and metrics |

## Architecture

```
┌─────────────────────────────────────────────────────────────┐
│                    Your Application                          │
├─────────────────────────────────────────────────────────────┤
│  chorus-engine-spring-boot-starter (Auto-Configuration)      │
├─────────────────────────────────────────────────────────────┤
│  Agent Loop │ StateGraph │ Swarm │ Harness │ Evals          │
├─────────────────────────────────────────────────────────────┤
│  HITL │ Guardrails │ Checkpoints │ Middleware │ Memory      │
├─────────────────────────────────────────────────────────────┤
│  Spring AI 2.0 (ChatClient, Advisors, MCP, Tools)           │
├─────────────────────────────────────────────────────────────┤
│  OpenAI │ Anthropic │ Gemini │ Bedrock │ Ollama │ Azure     │
└─────────────────────────────────────────────────────────────┘
```

## Features

### Core Agent Loop
- **ReAct pattern** with streaming async generators (Flux)
- **Per-chunk stream timeout** and automatic retry with exponential backoff
- **Zod-like structured output** validation with auto-correction
- **30+ structured event types** for full observability

### Middleware System (6 hooks)
- `beforeRound` / `afterRound`
- `extraSystemPrompt` / `extraTools`
- `maybeCompact` (context window management)
- `beforeTool` (cancel/substitute) / `afterTool`

### Human-in-the-Loop (HITL)
- Configurable timeout gates
- Session-level approvals
- Checkpoint-serializable for crash recovery

### Checkpointing
- JSON File (atomic tmp+rename)
- PostgreSQL + JSONB
- Durable event-sourced mode (sync/async/exit)
- `recoverFromCrash()` reconstruction

### StateGraph DAG
- Reducer semantics: `lastValue`, `append`, `sum`, `setUnion`, `mapMerge`
- Compile-time cycle detection (DFS)
- Runtime infinite-loop detection (state fingerprinting)
- **Java 25 Structured Concurrency** for parallel wave execution
- Per-node and per-graph timeouts
- Deadlock detection
- `Send` objects for dynamic subgraph fan-out

### Swarm Multi-Agent (4 paradigms)
1. **Handoff** - Sequential agent transfer with context filtering
2. **Graph/DAG** - Topological waves with `dependsOn`, parallel execution
3. **Supervisor** - Central coordinator routes to specialists
4. **Group Chat** - Round-robin debate with `vote`/`concatenate`/`first-success`

### Circuit Breaker & Cost Control
- Max consecutive same-agent (default 3)
- Per-agent token budgets (default 50K)
- Hard cost caps with `budget-exceeded` events
- Auto-downgrade to cheaper models under budget pressure

### Harness Semantic Routing
- Embedding-based intent classification (~94% accuracy)
- 7 task kinds: `answer_only`, `inspect_only`, `single_file_edit`, `multi_file_edit`, `debug`, `research`, `project_phase`
- Regex fallback for low-confidence routing

### Guardrails (3-tier defense)
- **Tier 1 (Fast)**: Regex/keywords (<1ms)
- **Tier 2 (ML)**: NER / embedding similarity (20-100ms)
- **Tier 3 (LLM)**: LLM-as-judge policy evaluation (500ms-8s)
- Adaptive thresholds from operator feedback

### Telemetry
- OpenTelemetry-native tracing
- Span types: `agent.loop`, `agent.round`, `agent.tool_call`, `harness.worker`, `swarm.execution`, `swarm.wave`
- Micrometer metrics: token usage, cost, latency, completion rates
- Auto-redaction of API keys from spans

### MCP Integration
- Spring AI 2.0 MCP client/server starters
- stdio, SSE, HTTP transports
- Auto-discovery and health checks
- Namespaced tools: `mcp__{server}__{tool}`

### A2A Protocol
- JSON-RPC 2.0 over HTTP
- AgentCard discovery
- Streaming SSE execution

### Adaptive Skill Runtime
- L0-L3 skill hierarchy (Primitives → Skills → Patterns → Metaskills)
- Semantic indexing with hot-reload
- Performance tracking with auto-curation (trusted/deprecated)

## Quick Start

```java
@SpringBootApplication
public class MyApp {

    @Bean
    public CommandLineRunner demo(ChatClient chatClient,
                                  ToolRegistry tools,
                                  HitlGate hitlGate) {
        return args -> {
            var config = AgentConfig.builder()
                .runId(UUID.randomUUID().toString())
                .systemPrompt("You are a senior Java engineer")
                .model("gpt-5-mini")
                .hitlEnabled(true)
                .build();

            var agent = new AgentLoop(chatClient, config,
                List.of(new SummarizationMiddleware()),
                checkpointer, hitlGate, guardrails, RetryPolicy.defaultPolicy());

            agent.run("Refactor this class to use Java 25 features")
                .subscribe(event -> System.out.println(event.type() + ": " + event));
        };
    }
}
```

## Modules

| Module | Purpose |
|--------|---------|
| `chorus-engine-core` | Events, reactive streams, checkpointing, middleware |
| `chorus-engine-agent` | ReAct loop, HITL, retry, memory |
| `chorus-engine-graph` | StateGraph DAG, channels, REST server |
| `chorus-engine-swarm` | Multi-agent orchestration, circuit breaker |
| `chorus-engine-harness` | Semantic routing, workers, protocols |
| `chorus-engine-llm` | Provider abstraction, Spring AI bridge |
| `chorus-engine-tools` | Filesystem, shell, git, web search |
| `chorus-engine-guardrails` | 3-tier defense, NER, redaction |
| `chorus-engine-skills` | Registry, embedder, synthesizer |
| `chorus-engine-telemetry` | OpenTelemetry + Micrometer |
| `chorus-engine-mcp` | Model Context Protocol |
| `chorus-engine-a2a` | Agent-to-Agent protocol |
| `chorus-engine-evals` | Evaluation framework |
| `chorus-engine-memory` | Short/long-term memory, compression |
| `chorus-engine-spring-boot-starter` | Auto-configuration |
| `chorus-engine-spring-boot-sample` | Example application |

## Building

```bash
./gradlew build
```

Requires **Java 25** and accepts preview features (Structured Concurrency, Stable Values).

## License

MIT
