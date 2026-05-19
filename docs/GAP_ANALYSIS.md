# Chorus Engine Java — Gap Analysis

**Version:** 1.0.0-SNAPSHOT  
**Date:** 2026-05-19  
**Framework LOC:** ~15,800 (production) + ~4,500 (tests)  
**Modules:** 10 (chorus-core, chorus-checkpoint, chorus-graph, chorus-swarm, chorus-tools, chorus-harness, chorus-guardrails, chorus-telemetry, chorus-spring-boot-starter, chorus-tests)

---

## Executive Summary

The Java port of Chorus Engine has **strong enterprise foundations** — Spring AI 2.0.0-M6 integration, production-grade persistence (PostgreSQL/Redis), distributed tracing, circuit breakers, and a reactor-based streaming loop. However, it remains **incomplete relative to cutting-edge 2026 agent frameworks** such as OpenAI Agents SDK, Google ADK, LangGraph (Python/JS), CrewAI, and AutoGen.

**Critical gaps** cluster around:
1. **Agent cognition** — no planning, no reflection, no hierarchical task decomposition
2. **Perception** — multimodal is types-only; computer use is interface-only
3. **Execution** — no parallel tool calls, no code sandbox, no persistent job queue
4. **Retrieval** — vector store exists but no end-to-end RAG pipeline
5. **Evaluation** — basic scoring exists but no LLM-as-a-Judge, no automatic regression suite

**Recommendation:** The framework is production-ready for **simple ReAct agents with persistence and observability**. It is **not yet ready** for complex multi-agent systems, computer-automation agents, or multimodal applications without additional implementation work.

---

## 1. Feature Inventory

### 1.1 Core Agent Runtime — ✅ Strong

| Feature | Status | Evidence | Notes |
|---------|--------|----------|-------|
| ReAct Loop | ✅ Complete | `AgentLoop.java` (364 LOC) | Streaming via `Flux<AgentEvent>`, middleware pipeline |
| Tool Use | ✅ Complete | `ChorusChatModel.java`, `AgentTool.java` | Full ReAct cycle; AgentLoop controls execution |
| Streaming | ⚠️ Partial | `SpringAiChatModelAdapter.java:197` | `generate()` works; `stream()` throws `UnsupportedOperationException` |
| Parallel Tool Execution | ❌ Missing | — | Tools execute sequentially in a `for` loop |
| Max Rounds / Budget | ✅ Complete | `LoopOptions.java` | Configurable round limits |
| Abort Signal | ✅ Complete | `AtomicBoolean` in `LoopOptions` | Cooperative cancellation |
| BTW Queue | ✅ Complete | `BtwQueue.java` | Break-the-fourth-wall user injection |

### 1.2 LLM Integration — ✅ Strong

| Feature | Status | Evidence | Notes |
|---------|--------|----------|-------|
| Spring AI Bridge | ✅ Complete | `SpringAiChatModelAdapter.java` (210 LOC) | M6-compatible; `toolCallbacks()` pattern |
| Provider Abstraction | ✅ Complete | `ChorusChatModel.java` | Interface allows non-Spring AI backends |
| Structured Output | ✅ Complete | `StructuredOutputGenerator.java` (216 LOC) | JSON Schema, POJO deserialization, retry loop |
| Model Pricing | ✅ Complete | `ModelPricing.java`, `CostTracker.java` | Per-model input/output cost estimation |
| Reasoning Parser | ❌ Missing | — | No explicit reasoning/chain-of-thought extraction |
| Context Window Tracking | ❌ Missing | — | No automatic context-window-exceeded handling |

### 1.3 Persistence — ✅ Production-Grade

| Feature | Status | Evidence | Notes |
|---------|--------|----------|-------|
| File Checkpointer | ✅ Complete | `JsonFileCheckpointer.java` | Atomic write (tmp + rename) |
| PostgreSQL | ✅ Complete | `PostgresCheckpointer.java` (281 LOC) | Schema versioning, transactions, batch, health check |
| Redis | ✅ Complete | `RedisCheckpointer.java` (170 LOC) | TTL, pipeline, hash-per-thread |
| Durable Mode | ✅ Complete | `DurableCheckpointer.java` | SYNC / ASYNC / EXIT modes |
| Fork / Branch | ✅ Complete | `Checkpointer.fork()` | Time-travel debugging support |
| Delete / GC | ✅ Complete | `Checkpointer.delete()` | — |

### 1.4 Resilience — ✅ Production-Grade

| Feature | Status | Evidence | Notes |
|---------|--------|----------|-------|
| Retry with Backoff | ✅ Complete | `RetryableChatModel.java` | Exponential + jitter, max delay cap |
| Circuit Breaker | ✅ Complete | `CircuitBreaker.java` | CLOSED/OPEN/HALF_OPEN, thread-safe |
| Tests | ✅ Complete | `CircuitBreakerTest.java`, `RetryableChatModelTest.java` | 15 tests covering state transitions, stress, backoff timing |

### 1.5 Observability — ✅ Strong

| Feature | Status | Evidence | Notes |
|---------|--------|----------|-------|
| Distributed Tracing | ✅ Complete | `TraceContext.java`, `TraceCarrier.java` | W3C `traceparent`/`baggage`, ThreadLocal propagation |
| Trace Wiring | ✅ Complete | `AgentLoop.java` | Span logs around LLM calls, loop completion |
| OpenTelemetry Bridge | ✅ Complete | `ChorusTelemetry.java`, `AgentSpanProcessor.java` | Span export, auto-config |
| Cost Tracking | ✅ Complete | `CostTracker.java` (core + telemetry) | Per-run cost aggregation |
| Token Usage Tracking | ✅ Complete | `TokenBudgetMiddleware.java` | Caffeine bounded cache |
| Metrics / Dashboard | ❌ Missing | — | No Micrometer/Prometheus integration |

### 1.6 Human-in-the-Loop — ✅ Complete

| Feature | Status | Evidence | Notes |
|---------|--------|----------|-------|
| Approval Policies | ✅ Complete | `ApprovalPolicy.java` | AUTO_EDIT, ASK, NEVER |
| Pause / Resume | ✅ Complete | `HitlGate.java`, checkpoint state | `HitlPause` serialized to storage |
| Resume Key | ✅ Complete | `CheckpointState.java` | Unique key per pause point |

### 1.7 Memory & Context — ⚠️ Mixed

| Feature | Status | Evidence | Notes |
|---------|--------|----------|-------|
| Vector Store | ✅ Complete | `ChorusVectorStore.java` (186 LOC) | Spring AI `VectorStore` + `EmbeddingModel` wrapper |
| In-Memory Vector Store | ✅ Complete | `InMemoryVectorStore.java` | Fallback for testing |
| Context Compaction | ⚠️ Partial | `TokenBudgetMiddleware.maybeCompact()` | Truncation-based only; no LLM summarization |
| Episodic Memory | ❌ Missing | — | No long-term memory of past conversations |
| Reflection / Self-Improvement | ❌ Missing | — | No agent self-evaluation loop |

### 1.8 Multi-Agent — ⚠️ Partial

| Feature | Status | Evidence | Notes |
|---------|--------|----------|-------|
| Sub-Agent Delegation | ✅ Complete | `SubAgentInvoker.java`, `IsolatedContext.java` | Isolated thread, trace propagation, fork |
| Swarm Orchestration | ⚠️ Partial | `SwarmOrchestrator.java` | Sequential agent handoff only; no parallel DAG execution |
| Graph-Based Swarm | ❌ Missing | `SwarmOrchestrator:41` | Explicitly returns error: "Graph swarm not yet implemented" |
| Group Chat | ❌ Missing | — | No persistent multi-agent conversation channel |
| Supervisor Pattern | ❌ Missing | — | No hierarchical supervisor-worker pattern |

### 1.9 Protocols & Integration — ⚠️ Mixed

| Feature | Status | Evidence | Notes |
|---------|--------|----------|-------|
| A2A Client | ✅ Complete | `A2aClient.java` (177 LOC) | Agent Card discovery, task delegation, trace propagation |
| A2A Server | ✅ Complete | `A2aServerController.java` (130 LOC) | HTTP controller for incoming tasks |
| MCP Provider | ✅ Complete | `McpToolProvider.java` (61 LOC) | Spring AI `SyncMcpToolCallbackProvider` bridge |
| MCP Auth | ❌ Missing | — | No OAuth/API key management for MCP servers |
| Web Search Tool | ❌ Missing | — | No Tavily, Bing, or Serper integration |
| Code Sandbox | ❌ Missing | — | No Docker-based code execution |

### 1.10 Perception (Multimodal + Computer Use) — ❌ Weak

| Feature | Status | Evidence | Notes |
|---------|--------|----------|-------|
| Multimodal Types | ⚠️ Partial | `MultimodalChatMessage.java`, `MediaContent.java` | Types exist; no vision model integration |
| Image Input | ❌ Missing | — | No base64 image encoding for LLM APIs |
| Audio Input/Output | ❌ Missing | — | No STT/TTS integration |
| Computer Use Interface | ⚠️ Partial | `ComputerUseTool.java` | Schema defined; **no concrete implementation** |
| Computer Use Impl | ❌ Missing | — | No Playwright, Selenium, or OS automation |
| Screenshot + VLM | ❌ Missing | — | No visual understanding loop |

### 1.11 Routing & Orchestration — ✅ Strong

| Feature | Status | Evidence | Notes |
|---------|--------|----------|-------|
| Semantic Router | ✅ Complete | `SemanticRouter.java` (191 LOC) | Embedding-based cosine similarity, virtual threads |
| Route Caching | ✅ Complete | `embeddingCache` in `SemanticRouter` | Lazy embedding computation |
| Worker Engine | ✅ Complete | `WorkerEngine.java` | Task dispatch to workers |

### 1.12 Graph & Visualization — ⚠️ Partial

| Feature | Status | Evidence | Notes |
|---------|--------|----------|-------|
| State Graph | ✅ Complete | `StateGraph.java`, `CompiledGraph.java` | Nodes, edges, channels, conditional routing |
| Graph REST Server | ✅ Complete | `GraphRestServer.java` | HTTP API for graph execution |
| Graph Interrupts | ✅ Complete | `GraphInterrupt.java` | Pause/resume within graph flow |
| Mermaid Viz | ⚠️ Unknown | `GraphVisualizer.java` | Needs inspection |
| PlantUML Viz | ⚠️ Unknown | — | Needs inspection |

### 1.13 Evaluation — ⚠️ Partial

| Feature | Status | Evidence | Notes |
|---------|--------|----------|-------|
| Eval Harness | ✅ Complete | `EvalHarness.java` (170 LOC) | Dataset loading, runner, scoring, report generation |
| Custom Scorers | ✅ Complete | `Scorer.java` interface | Pluggable scoring functions |
| LLM-as-a-Judge | ❌ Missing | — | No rubric-based LLM evaluation |
| Regression Suite | ❌ Missing | — | No automatic before/after comparison |
| CI Integration | ❌ Missing | — | No GitHub Actions eval pipeline |

### 1.14 Guardrails — ✅ Complete

| Feature | Status | Evidence | Notes |
|---------|--------|----------|-------|
| Tiered Engine | ✅ Complete | `TieredGuardrailEngine.java` | Severity levels, actions |
| Content Filtering | ⚠️ Partial | — | Basic structure; no PII detection, no toxicity model |

### 1.15 Tokenization — ✅ Excellent

| Feature | Status | Evidence | Notes |
|---------|--------|----------|-------|
| BPE Core | ✅ Complete | `BpeCore.java`, `BpeTokenizer.java` | Byte-pair encoding |
| Tiktoken Loader | ✅ Complete | `TiktokenLoader.java` | cl100k_base, o200k_base, etc. |
| Model Registry | ✅ Complete | `TokenizerRegistry.java` | 40+ models |
| Approximate Fallback | ✅ Complete | `ApproximateTokenizer.java` | 4-char heuristic for unknown models |
| Token Estimator | ✅ Complete | `TokenCountEstimator.java` | Per-message counting |

### 1.16 Spring Boot Integration — ✅ Complete

| Feature | Status | Evidence | Notes |
|---------|--------|----------|-------|
| Auto-Configuration | ✅ Complete | `ChorusAutoConfiguration.java` | Conditional beans |
| Properties | ✅ Complete | `ChorusProperties.java` | Type-safe configuration |
| Harness Auto-Config | ✅ Complete | `HarnessAutoConfiguration.java` | Router, worker engine beans |
| Telemetry Auto-Config | ✅ Complete | `TelemetryAutoConfiguration.java` | OTel auto-wiring |

---

## 2. Comparison with Leading Frameworks

| Capability | Chorus Java | OpenAI Agents SDK | Google ADK | LangGraph | CrewAI |
|-----------|:-----------:|:-----------------:|:----------:|:---------:|:------:|
| ReAct Loop | ✅ | ✅ | ✅ | ✅ | ✅ |
| Streaming | ⚠️ | ✅ | ✅ | ✅ | ❌ |
| Parallel Tool Calls | ❌ | ✅ | ✅ | ✅ | ❌ |
| HITL | ✅ | ✅ | ✅ | ✅ | ❌ |
| Checkpoints | ✅ | ❌ | ⚠️ | ✅ | ❌ |
| PostgreSQL/Redis | ✅ | ❌ | ❌ | ✅ | ❌ |
| Distributed Tracing | ✅ | ❌ | ❌ | ⚠️ | ❌ |
| Circuit Breaker | ✅ | ❌ | ❌ | ❌ | ❌ |
| Spring Boot | ✅ | ❌ | ❌ | ❌ | ❌ |
| A2A Protocol | ✅ | ❌ | ❌ | ❌ | ❌ |
| MCP | ✅ | ❌ | ❌ | ⚠️ | ❌ |
| Semantic Router | ✅ | ❌ | ❌ | ⚠️ | ❌ |
| Planning / Hierarchy | ❌ | ✅ | ✅ | ✅ | ✅ |
| RAG Pipeline | ❌ | ❌ | ✅ | ✅ | ⚠️ |
| Computer Use | ❌ | ✅ | ❌ | ⚠️ | ❌ |
| Multimodal | ❌ | ✅ | ✅ | ⚠️ | ❌ |
| Code Sandbox | ❌ | ✅ | ⚠️ | ⚠️ | ❌ |
| Web Search | ❌ | ✅ | ✅ | ⚠️ | ✅ |
| LLM-as-Judge | ❌ | ❌ | ❌ | ⚠️ | ❌ |
| Group Chat | ❌ | ❌ | ❌ | ✅ | ❌ |

**Key takeaway:** Chorus Java leads in **enterprise infrastructure** (persistence, resilience, tracing, Spring ecosystem). It lags in **agent cognition** (planning, reflection) and **perception** (multimodal, computer use).

---

## 3. Gap Priority Matrix

### 🔴 P0 — Blocks Production Use for Advanced Agents

| Gap | Business Impact | Effort | Recommended Action |
|-----|-----------------|--------|-------------------|
| Streaming not implemented | Real-time UX broken | Medium | Implement `SpringAiChatModelAdapter.stream()` using `ChatClient.stream()` |
| Parallel tool execution | Latency multiplies by tool count | Medium | Change `AgentLoop` tool loop to `CompletableFuture.allOf()` |
| No RAG pipeline | Can't do knowledge-grounded Q&A | Large | Add document chunking, embedding, retrieval, re-ranking glue |
| No planning module | Complex tasks fail without decomposition | Large | Implement hierarchical planner with `Plan → Execute → Verify` loop |

### 🟡 P1 — Needed for Competitive Parity

| Gap | Business Impact | Effort | Recommended Action |
|-----|-----------------|--------|-------------------|
| Computer use interface only | Can't build GUI automation agents | Large | Implement Playwright bridge for `ComputerUseTool` |
| No multimodal integration | Can't process images/audio | Large | Add vision model adapter, base64 image encoding |
| No web search | Agent knowledge is stale | Small | Integrate Tavily/Bing API as a tool |
| No conversation summarization | Context loss is crude truncation | Medium | Add LLM-powered summarization middleware |
| No persistent job queue | Jobs lost on restart | Medium | Add Redis/RabbitMQ-backed job queue |
| No metrics (Micrometer) | Can't monitor in production | Small | Add Micrometer counters for loop rounds, tool calls, latencies |

### 🟢 P2 — Nice to Have

| Gap | Business Impact | Effort | Recommended Action |
|-----|-----------------|--------|-------------------|
| No LLM-as-a-Judge | Eval quality is lower | Medium | Add rubric-based LLM scorer |
| No code sandbox | Can't self-debug code | Large | Add Docker-based `exec` sandbox |
| No fine-tuning pipeline | Can't specialize models | Large | Out of scope — use external tools |
| No agent marketplace | No discovery | Large | Out of scope — use A2A + registry |

---

## 4. Risk Assessment

| Risk | Severity | Mitigation |
|------|----------|------------|
| `stream()` throws `UnsupportedOperationException` | **High** — Any user trying streaming will hit a crash | Fix immediately (P0) |
| Computer use is interface-only | **Medium** — Marketing claims may mislead | Document as "extensible interface, bring your own implementation" |
| Swarm graph mode not implemented | **Low** — Error message is clear | Complete or remove the stub |
| No parallel tool execution | **Medium** — Performance degradation on multi-tool agents | Refactor tool loop to async composition |
| Caffeine cache eviction is synchronous | **Low** — `executor(Runnable::run)` may block | Consider `ForkJoinPool.commonPool()` for production |

---

## 5. Recommendations

### Short Term (Next 2 Weeks)
1. **Fix streaming** — Implement `SpringAiChatModelAdapter.stream()` using Spring AI's `.stream().chatResponse()` Flux
2. **Add parallel tool execution** — Refactor `AgentLoop` sequential tool loop to `CompletableFuture.allOf()`
3. **Add Micrometer metrics** — Instrument `AgentLoop` with counters for rounds, tool calls, LLM latency
4. **Add web search tool** — Integrate Tavily or Serper as a built-in tool

### Medium Term (Next 2 Months)
1. **Build RAG pipeline** — Document chunking (recursive), embedding, vector search, re-ranking, context injection
2. **Add conversation summarization** — Replace truncation compaction with LLM-powered summary middleware
3. **Implement computer use** — Playwright bridge for screenshot → click → type automation
4. **Add planning module** — Hierarchical task network (HTN) or LLM-based planner

### Long Term (Next 6 Months)
1. **Multimodal support** — Vision model adapter, base64 image encoding, audio I/O
2. **Persistent job queue** — Redis Streams or RabbitMQ-backed agent job execution
3. **LLM-as-a-Judge** — Automatic eval scoring with rubric-based LLM critique
4. **Agent reflection** — Self-evaluation loop for continuous improvement

---

*This analysis is based on codebase inspection at commit HEAD. Features marked "Complete" have working implementations with passing tests. Features marked "Missing" have no implementation or only placeholder types.*
