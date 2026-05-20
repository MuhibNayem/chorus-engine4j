# Chorus Engine

> Java-native agentic AI framework for production-grade multi-agent systems.

[![Java CI](https://github.com/MuhibNayem/chorus-engine4j/actions/workflows/java-ci.yml/badge.svg)](https://github.com/MuhibNayem/chorus-engine4j/actions/workflows/java-ci.yml)
[![Java 25](https://img.shields.io/badge/Java-25-blue.svg)](https://openjdk.org/projects/jdk/25/)
[![Spring Boot 4.0](https://img.shields.io/badge/Spring%20Boot-4.0-brightgreen.svg)](https://spring.io/projects/spring-boot)
[![License](https://img.shields.io/badge/License-Apache%202.0-blue.svg)](https://opensource.org/licenses/Apache-2.0)

## What is Chorus Engine?

Chorus Engine is a **Java-native** framework for building agentic AI systems — autonomous agents that reason, plan, use tools, remember context, and collaborate. Built for the JVM, it brings the power of modern agentic AI to Java developers without requiring Python interop.

```java
@Agent(name = "assistant", systemPrompt = "You are a helpful coding assistant.")
@Component
public class DeveloperAgent {

    @Tool(description = "Runs tests and returns output")
    public String runTests(@ToolParam(description = "Module name") String module) {
        return "./gradlew :" + module + ":test - successful";
    }
}
```

## Features

| Feature | Status | Module |
|---|---|---|
| ReAct Agent Loop with parallel tool execution | ✅ | `agent` |
| Human-in-the-Loop (HITL) approval gates | ✅ | `agent` |
| Self-healing agent loop | ✅ | `agent` |
| Deterministic DAG workflows with checkpointing | ✅ | `graph` |
| Multi-agent swarms (handoff, consensus, supervisor, planner-executor) | ✅ | `swarm` |
| Hybrid RAG (dense + keyword) | ✅ | `rag` |
| Self-RAG / Corrective RAG / Agentic RAG | ✅ | `rag` |
| Incremental RAG streaming | ✅ | `rag` |
| Four-layer memory (short-term, long-term, episodic, procedural) | ✅ | `memory` |
| Tiered guardrails with PII redaction | ✅ | `guardrails` |
| Semantic skill routing | ✅ | `skills` |
| Event-driven telemetry with OpenTelemetry | ✅ | `telemetry` |
| MCP (Model Context Protocol) client/server | ✅ | `mcp` |
| A2A (Agent-to-Agent) protocol | ✅ | `a2a` |
| Evaluation framework with LLM-as-judge | ✅ | `evals` |
| Spring Boot auto-configuration | ✅ | `spring-boot-starter` |
| GraalVM native image support | ✅ | All modules |

## Quick Start

### With Spring Boot (Recommended)

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
```

```java
@Service
public class ChatService {
    // Injected automatically by the framework from the DeveloperAgent definition
    private final AgentLoop assistantAgentLoop;

    public ChatService(AgentLoop assistantAgentLoop) {
        this.assistantAgentLoop = assistantAgentLoop;
    }

    public Flow.Publisher<AgentEvent> ask(String question) {
        return assistantAgentLoop.run(question, CancellationToken.never());
    }
}
```

### Without Spring Boot

```java
// Build everything programmatically
HttpClient httpClient = HttpClient.newHttpClient();
ObjectMapper mapper = new ObjectMapper();

ProviderRegistry registry = ProviderRegistry.defaults(httpClient, mapper);
LlmClient llm = registry.get("openai");

ToolRegistry tools = new ToolRegistry();
tools.register(new CalculatorTool());

AgentLoop agent = new AgentLoop(llm, tools, new InMemoryEventBus(),
    AgentLoopConfig.builder().maxRounds(10).build());

agent.run("What is 2 + 2?", CancellationToken.never())
    .subscribe(event -> { /* handle events */ });
```

## Architecture

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

## Modules

| Module | Description | Dependencies |
|---|---|---|
| [`chorus-engine-core`](chorus-engine-core/) | Foundation types: events, results, messages, cancellation, vector ops | None |
| [`chorus-engine-tokenizer`](chorus-engine-tokenizer/) | Token counting for 50+ models | `core` |
| [`chorus-engine-llm`](chorus-engine-llm/) | LLM clients, streaming, embeddings | `core`, `tokenizer` |
| [`chorus-engine-agent`](chorus-engine-agent/) | ReAct agent loop, HITL, middleware | `core`, `llm` |
| [`chorus-engine-graph`](chorus-engine-graph/) | DAG workflows, channels, checkpointing | `core`, `agent` |
| [`chorus-engine-swarm`](chorus-engine-swarm/) | Multi-agent orchestration | `core`, `llm`, `agent`, `tools` |
| [`chorus-engine-harness`](chorus-engine-harness/) | Evaluation harness, semantic routing | Multiple |
| [`chorus-engine-tools`](chorus-engine-tools/) | Tool registry, schema generation | `core`, `llm` |
| [`chorus-engine-guardrails`](chorus-engine-guardrails/) | Safety guardrails, PII redaction | `core`, `llm` |
| [`chorus-engine-skills`](chorus-engine-skills/) | Semantic skill discovery and routing | Multiple |
| [`chorus-engine-telemetry`](chorus-engine-telemetry/) | Metrics, cost tracking, OpenTelemetry | Multiple |
| [`chorus-engine-mcp`](chorus-engine-mcp/) | MCP protocol client/server | `core`, `tools` |
| [`chorus-engine-a2a`](chorus-engine-a2a/) | A2A protocol implementation | `core` |
| [`chorus-engine-evals`](chorus-engine-evals/) | Evaluation framework | Multiple |
| [`chorus-engine-memory`](chorus-engine-memory/) | Multi-layer memory system | `core`, `tokenizer`, `llm` |
| [`chorus-engine-rag`](chorus-engine-rag/) | Advanced RAG pipelines | Multiple |
| [`chorus-engine-spring-boot-starter`](chorus-engine-spring-boot-starter/) | Auto-configuration for Spring Boot | All modules |

## Documentation

| Document | Description |
|---|---|
| [User Guide](docs/GUIDE.md) | Comprehensive feature documentation with examples |
| [Architecture](docs/ARCHITECTURE.md) | System architecture and design principles |
| [Harness](docs/HARNESS.md) | Evaluation harness deep-dive |
| [Router](docs/ROUTER.md) | Semantic task routing internals |
| [Maven Central Publishing](docs/MAVEN-CENTRAL-PUBLISHING.md) | Release process |
| [GraalVM Native Analysis](docs/GRAALVM-NATIVE-ANALYSIS.md) | Native image compatibility |
| [Contributing](CONTRIBUTING.md) | Build, test, and PR guidelines |
| [Changelog](CHANGELOG.md) | Version history |

## Requirements

- Java 25
- Gradle 9.1.0+
- `--enable-preview` flag (for Structured Concurrency)
- `--add-modules jdk.incubator.vector` (for SIMD vector operations)

## Building

```bash
cd chorus-engine-java
./gradlew test          # Run all 1,015 tests
./gradlew compileJava   # Compile all modules
./gradlew bootJar       # Build Spring Boot sample
```

## GraalVM Native Image

```bash
./gradlew :chorus-engine-spring-boot-sample:nativeCompile
```

See [docs/GRAALVM-NATIVE-ANALYSIS.md](docs/GRAALVM-NATIVE-ANALYSIS.md) for details.

## Why Java?

- **Type safety** with records and sealed types
- **Virtual threads** for massive concurrency
- **GraalVM native images** for sub-100ms cold starts
- **Enterprise integration** with existing Spring Boot microservices
- **No Python dependency hell** — single JAR deployment

## License

Apache License 2.0
