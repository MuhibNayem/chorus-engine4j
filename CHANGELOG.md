# Changelog

All notable changes to Chorus Engine will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

### Added
- GraalVM Native Image compatibility with reachability metadata for all sealed types
- `reflect-config.json` and `resource-config.json` for native image compilation
- Native image CI job in GitHub Actions
- Java CI workflow for compile + test on JDK 25
- Comprehensive module READMEs for all 17 modules
- Unified User Guide (`docs/GUIDE.md`) covering all features with examples
- Maven Central publishing guide (`docs/MAVEN-CENTRAL-PUBLISHING.md`)

### Changed
- `OpenTelemetryBridge`: Removed `Class.forName` probe for native image compatibility
- `VectorOpsProvider`: Removed `Class.forName` probe for native image compatibility
- `ChorusAutoConfiguration`: `ObjectMapper` now registers `JavaTimeModule`
- Gradle wrapper upgraded from 8.11 to 9.1.0 for Java 25 support

### Fixed
- `AgentLoop` deadlock: `executeToolsParallel()` now uses `ForkJoinPool` instead of shared executor
- `FlowCollector` early cancellation check in `toList()` and `last()`

## [0.1.0-SNAPSHOT] - 2026-05-19

### Added
- **Core module**: Immutable data structures (`AgentEvent`, `Result`, `Message`, `CancellationToken`, `FlowCollector`, `VectorOperations`)
- **Tokenizer module**: BPE and approximate tokenizers for 50+ models
- **LLM module**: Unified `LlmClient` with OpenAI, Anthropic, Gemini, vLLM providers; streaming; tool calling; retry and circuit breaker; embedding clients
- **Agent module**: ReAct agent loop with parallel tool execution, HITL gates, middleware, self-healing
- **Graph module**: Deterministic DAG execution (`StateGraph`), channels, checkpointing (memory/JDBC/Redis), speculative execution
- **Swarm module**: Multi-agent orchestration with handoff, consensus, supervisor, and planner-executor patterns
- **Harness module**: Evaluation harness with semantic task routing, trajectory logging, project memory
- **Tools module**: Tool registry, schema generation, built-in shell and filesystem tools
- **Guardrails module**: Tiered safety engine with PII redaction, embedding similarity, LLM judge
- **Skills module**: Semantic skill discovery, routing, and JSON-based skill loading
- **Telemetry module**: Event-driven observability with metrics, cost tracking, provenance, OpenTelemetry bridge
- **MCP module**: Model Context Protocol client/server with HTTP SSE and stdio transports
- **A2A module**: Agent-to-Agent protocol with multi-tenancy and signed Agent Cards
- **Evals module**: Evaluation framework with LLM-as-judge, semantic similarity, parallel execution
- **Memory module**: Four-layer memory (short-term, long-term, episodic, procedural) with hierarchical manager
- **RAG module**: Advanced RAG with hybrid retrieval, self-RAG, corrective RAG, agentic RAG, incremental streaming
- **Spring Boot Starter**: Auto-configuration wiring all 17 modules with conditional properties
- **Spring Boot Sample**: Minimal sample application with REST endpoints
- **Architecture documentation** (`docs/ARCHITECTURE.md`)
- **Harness documentation** (`docs/HARNESS.md`)
- **Router documentation** (`docs/ROUTER.md`)
- **Implementation plan** (`docs/PLAN.md`)
- **1,015 tests** across all modules with zero failures
