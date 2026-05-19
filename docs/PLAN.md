# Chorus Engine Java — Production Implementation Plan

> **Version:** 0.1.0-draft  
> **Date:** 2026-05-19  
> **Status:** Engineering Plan — Approved for Implementation  
> **Principle:** Zero external AI framework dependencies. Everything built from scratch.

---

## 1. Executive Summary

Chorus Engine Java is a **from-scratch, dependency-light, production-grade multi-agent orchestration runtime** for the JVM. It solves the critical gap in the Java ecosystem: there is no first-class agentic AI framework that does not couple to Python-centric abstractions or vendor-specific SDKs.

### Why From Scratch?

| Existing Solution | Why We Avoid It |
|---|---|
| Spring AI | Couples to Spring ecosystem; hides token counts; no agent orchestration |
| LangChain4j | Bridges to Python concepts; heavy dependency tree; limited control |
| Raw HTTP clients | No retry, streaming, token counting, context management |
| Python interop | Serialization overhead; JVM GC pressure; deployment complexity |

### Target Persona

- **Principal Engineers** at Fortune 500 companies running Java microservices
- **AI Platform Teams** building internal agent infrastructure
- **Fintech/Healthcare** developers who need auditability, guardrails, and deterministic control

---

## 2. Architecture Principles

1. **Zero AI Framework Coupling** — We speak HTTP/JSON to LLMs. We parse SSE streams. We count tokens ourselves.
2. **Immutability by Default** — All configuration, state, and events are immutable. No hidden mutation.
3. **Fail-Fast with Rich Errors** — Sealed `Result<T, E>` types, not exceptions. Every failure carries context.
4. **Observable by Design** — Every token, every tool call, every agent handoff emits a structured event.
5. **Thread-Safe Concurrent Execution** — Agents run in parallel using Java 25 Structured Concurrency.
6. **Token-Aware Everywhere** — Context windows, cost budgets, and compaction are first-class, not afterthoughts.

---

## 3. Module Architecture

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                           chorus-engine-parent                               │
├─────────────────────────────────────────────────────────────────────────────┤
│  API Layer (Optional)                                                        │
│  ├── chorus-engine-rest        REST API + SSE streaming server              │
│  └── chorus-engine-spring      Spring Boot auto-configuration (optional)    │
├─────────────────────────────────────────────────────────────────────────────┤
│  Orchestration Layer                                                         │
│  ├── chorus-engine-swarm       Multi-agent: handoff, DAG, supervisor, debate│
│  ├── chorus-engine-graph       StateGraph DAG with checkpointing            │
│  └── chorus-engine-harness     Semantic routing + worker engine             │
├─────────────────────────────────────────────────────────────────────────────┤
│  Agent Layer                                                                 │
│  ├── chorus-engine-agent       ReAct loop, HITL, middleware, retry          │
│  └── chorus-engine-eval        Evaluation framework + regression testing    │
├─────────────────────────────────────────────────────────────────────────────┤
│  Foundation Layer                                                            │
│  ├── chorus-engine-llm         HTTP client, streaming, provider registry    │
│  ├── chorus-engine-tokenizer   BPE, tiktoken, SentencePiece, approximators  │
│  ├── chorus-engine-tools       Filesystem, shell, git, web search           │
│  ├── chorus-engine-guardrails  3-tier defense, adaptive thresholds          │
│  ├── chorus-engine-memory      Short-term, long-term, compression           │
│  ├── chorus-engine-skills      Adaptive skill runtime + trajectory synthesis│
│  ├── chorus-engine-mcp         Model Context Protocol client/server         │
│  ├── chorus-engine-a2a         Agent-to-Agent protocol                      │
│  └── chorus-engine-telemetry   OpenTelemetry + Micrometer integration       │
├─────────────────────────────────────────────────────────────────────────────┤
│  Core Layer (Zero External Dependencies)                                     │
│  └── chorus-engine-core        Events, types, Result<T,E>, reactive streams │
└─────────────────────────────────────────────────────────────────────────────┘
```

---

## 4. Core Design Patterns

### 4.1 Result<T, E> — Railway-Oriented Programming

Every operation that can fail returns `Result<T, E>`. No checked exceptions. No null returns.

```java
public sealed interface Result<T, E> {
    record Ok<T, E>(T value) implements Result<T, E> {}
    record Err<T, E>(E error, Context context) implements Result<T, E> {}
}
```

### 4.2 Event Sourcing for Agent State

Agent state is not a mutable object. It is a log of `AgentEvent`s. Replay the log to reconstruct state.

```java
public sealed interface AgentEvent {
    String runId();
    Instant timestamp();
    // 30+ event types: Token, ToolCall, Checkpoint, Handoff, GuardrailTrigger, etc.
}
```

### 4.3 Token-Aware Context Management

Every message carries a `TokenCount`. The `ContextWindow` tracks usage and triggers compaction.

```java
public record TokenCount(int inputTokens, int outputTokens, String tokenizerName) {}

public interface ContextWindow {
    Result<ContextWindow, ContextError> addMessage(Message message);
    Result<ContextWindow, ContextError> compact(CompactionStrategy strategy);
    boolean isWithinThreshold(double threshold);
}
```

### 4.4 Structured Concurrency for Parallel Execution

Java 25's `StructuredTaskScope` is used for:
- Parallel tool execution
- DAG wave execution
- Multi-agent fan-out
- Embeddings batch computation

---

## 5. Implementation Phases

### Phase 1: Foundation (Weeks 1-2)

**Goal:** Core types, Result pattern, tokenizer, and HTTP LLM client.

| Module | Deliverable | Acceptance Criteria |
|--------|-------------|---------------------|
| `core` | `Result<T,E>`, `AgentEvent` sealed hierarchy, `CancellationToken`, reactive `Publisher<T>` | 100% branch coverage on Result combinators |
| `tokenizer` | `BpeTokenizer`, `TiktokenLoader`, `SentencePieceTokenizer`, `ApproximateTokenizer`, `TokenizerRegistry` | Counts tokens within ±1% of Python tiktoken for 10K test strings |
| `llm` | `LlmClient` (JDK HttpClient), SSE streaming parser, `ProviderRegistry`, retry with exponential backoff + jitter | Streams 1M tokens without memory leak; p99 retry latency < 30s |

**Critical Design Decision:** `LlmClient` uses JDK 11+ `HttpClient` with HTTP/2 multiplexing. No Netty. No Spring WebFlux. Minimal deps.

### Phase 2: Single Agent (Weeks 3-4)

**Goal:** Production ReAct loop with full observability.

| Module | Deliverable | Acceptance Criteria |
|--------|-------------|---------------------|
| `agent` | `AgentLoop` with streaming, `Middleware` chain (6 hooks), `HitlGate`, `CheckpointManager` | Handles 100 concurrent agents; checkpoint recovery in < 100ms |
| `guardrails` | `TieredGuardrailEngine` (Fast/ML/LLM tiers), `AdaptiveThreshold`, `HybridRedaction` | <1ms Tier 1, <100ms Tier 2, configurable Tier 3 timeout |
| `memory` | `ShortTermMemory` (Caffeine), `LongTermMemory` (BM25 + embedding hybrid), `ContextCompactor` | Compaction preserves semantic meaning; recall@5 > 80% |
| `tools` | `ToolRegistry`, `FilesystemTool`, `ShellTool`, `GitTool`, `WebSearchTool` | Sandboxed fs; shell commands audited; git ops atomic |

**Critical Design Decision:** Agent loop is an async generator implemented as a `Publisher<AgentEvent>`. Each round is a structured task. Tool calls execute in parallel via `StructuredTaskScope`.

### Phase 3: Multi-Agent Orchestration (Weeks 5-6)

**Goal:** Graph execution and swarm intelligence.

| Module | Deliverable | Acceptance Criteria |
|--------|-------------|---------------------|
| `graph` | `StateGraph` builder, channel reducers, compile-time cycle detection, `CheckpointedGraphExecutor` | 100-node graph executes in < 5s; cycle detection fails at compile time |
| `swarm` | `HandoffOrchestrator`, `DagOrchestrator`, `SupervisorOrchestrator`, `DebateOrchestrator`, `CircuitBreaker` | Circuit breaker trips in < 10ms; cost router saves > 30% on budget pressure |
| `harness` | `SemanticTaskRouter` (embedding-based), `WorkerEngine` (parallel/pipeline modes), `ExecutionProtocol` | 94%+ routing accuracy on test corpus; worker fault tolerance |

**Critical Design Decision:** Graph state uses persistent data structures (immutable maps/lists) for efficient snapshotting. Reducers are pure functions.

### Phase 4: Enterprise Integration (Weeks 7-8)

**Goal:** Protocols, evaluation, and observability.

| Module | Deliverable | Acceptance Criteria |
|--------|-------------|---------------------|
| `mcp` | MCP client (stdio/SSE/HTTP), tool discovery, health checks, `McpToolAdapter` | Discovers 50+ tools in < 2s; auto-reconnect on disconnect |
| `a2a` | A2A server (JSON-RPC 2.0), `AgentCard`, streaming SSE endpoint | OpenAI Swarm-compatible agent discovery |
| `evals` | `EvalSuite`, `ExactMatchScorer`, `ContainsScorer`, `LlmJudgeScorer`, regression dashboard | Eval runs complete in < 5 min for 100 cases; drift detection |
| `telemetry` | `OpenTelemetryTracer`, `MicrometerMetrics`, `CostTracker`, `TokenUsageReporter` | Every span links to runId; cost tracked per-tenant |

### Phase 5: DX & Packaging (Week 9)

**Goal:** Developer experience that rivals Python frameworks.

| Deliverable | Detail |
|-------------|--------|
| Fluent API | `Agent.builder().model("gpt-4o").systemPrompt("...").tools(tools).build()` |
| YAML Config | `chorus.yml` for agent definitions, tool permissions, budget limits |
| CLI | `chorus run agent.yml --input "..."` for local execution |
| Spring Boot Starter | Optional auto-configuration; no Spring in core modules |
| GraalVM Native | Zero-reflection native image compilation |
| Documentation | Architecture Decision Records (ADRs), API reference, migration guides |

---

## 6. Tokenizer Architecture (Deep Dive)

### 6.1 Problem Statement

Java has no production-grade tokenizer library. Existing options:
- `tiktoken` (Python/Rust) — JNI bindings are brittle
- HuggingFace `tokenizers` (Rust) — same JNI problem
- `jtokkit` (Java) — Only cl100k_base; no SentencePiece; unmaintained
- Manual approximation — ±50% error on non-English text

### 6.2 Solution: Multi-Backend Tokenizer

```java
public interface Tokenizer {
    List<Integer> encode(String text);
    String decode(List<Integer> tokens);
    int countTokens(String text);
    int countChatTokens(String role, String content);
}
```

**Backends:**

| Backend | Models Supported | Accuracy | Implementation |
|---------|-----------------|----------|----------------|
| `BpeTokenizer` | GPT-4, GPT-3.5, GPT-5, Llama 3, DeepSeek, Qwen | Exact | Pure Java BPE with regex pre-tokenizer |
| `SentencePieceTokenizer` | Llama 2, Gemma, Mistral, T5 | Exact | Binary `.model` parser + greedy longest-match |
| `ApproximateTokenizer` | Claude, Gemini, Grok, Kimi, MiniMax, GLM | ±15% | Script-aware heuristic with per-language multipliers |
| `ApiTokenizer` | Claude, Gemini | Exact | Calls provider's count_tokens API (cached) |

**BpeTokenizer Implementation:**
- Load `.tiktoken` files (base64 token + rank per line)
- Pre-tokenize with compiled regex (cl100k_base or o200k_base pattern)
- BPE merge using rank-sorted merge table
- LRU cache for frequent byte sequences
- Thread-safe: immutable merge table, concurrent rank cache

**SentencePiece Implementation:**
- Parse protobuf `.model` files (simplified wire format parser)
- Support BPE and Unigram model types
- Byte fallback for unknown characters
- ▁ (U+2581) prefix handling for word boundaries

**ApproximateTokenizer Implementation:**
- Detect dominant script (Latin, CJK, Arabic, Thai, Code)
- Apply per-script multipliers (CJK: 2.2x, Arabic: 2.5x, Code: 1.5x)
- Segment text by script for mixed-language accuracy
- Calibrate against known token counts from public APIs

### 6.3 TokenizerRegistry

```java
public class TokenizerRegistry {
    public Tokenizer forModel(String modelName); // "gpt-4o" → o200k_base
    public Tokenizer forProvider(String provider, String model);
}
```

Pre-configured mappings for 50+ models across all major providers.

---

## 7. LLM Client Architecture (Deep Dive)

### 7.1 Design Goals

- **Zero framework dependencies** — JDK HttpClient only
- **Streaming first** — SSE parsing without blocking
- **Provider-agnostic** — Same interface for OpenAI, Anthropic, Gemini, etc.
- **Resilient** — Exponential backoff, circuit breaker, request coalescing

### 7.2 Core Abstraction

```java
public interface LlmClient {
    Publisher<StreamEvent> stream(ChatRequest request);
    CompletableFuture<ChatResponse> complete(ChatRequest request);
    HealthStatus health();
}

public record ChatRequest(
    String model,
    List<Message> messages,
    List<ToolDefinition> tools,
    double temperature,
    int maxTokens,
    ResponseFormat responseFormat
) {}
```

### 7.3 Streaming Architecture

```
HTTP/2 Request → SSE Parser → Token Buffer → Backpressure-aware Publisher
                    ↓
            StructuredTaskScope.fork() per chunk
                    ↓
            AgentEvent.StreamToken(token, latencyNanos)
```

**SSE Parser:**
- Handles `data: {...}` lines
- Parses JSON incrementally (Jackson Streaming API)
- Extracts delta tokens from OpenAI/Anthropic formats
- Normalizes to unified `StreamEvent` type

**Backpressure:**
- Uses `java.util.concurrent.Flow` (Reactive Streams) API
- Subscriber controls demand via `request(n)`
- Overflow drops oldest buffered tokens (configurable)

### 7.4 Provider Implementations

| Provider | Transport | Auth | Special Handling |
|----------|-----------|------|-----------------|
| OpenAI | HTTP/2 SSE | Bearer token | `delta` field in SSE |
| Anthropic | HTTP/2 SSE | x-api-key | `type: content_block_delta` |
| Gemini | HTTP/2 SSE | API key in query | `candidates[0].content.parts` |
| Ollama | HTTP/1.1 SSE | None | Local endpoint |
| vLLM | HTTP/2 SSE | Bearer token | OpenAI-compatible |
| Azure OpenAI | HTTP/2 SSE | Azure AD / API key | Custom endpoint + api-version |
| DeepSeek | HTTP/2 SSE | Bearer token | Reasoning content separate |
| Grok | HTTP/2 SSE | Bearer token | xAI-specific headers |

### 7.5 Retry & Resilience

```java
public record RetryPolicy(
    int maxAttempts,
    Duration baseDelay,
    Duration maxDelay,
    double jitterFactor,
    Set<Integer> retryableStatusCodes, // 429, 502, 503, 504
    Set<String> retryableErrorCodes     // "rate_limit", "overloaded"
) {}
```

- **Per-chunk timeout:** If no token received in N seconds, retry the connection
- **Fatal boundary:** After first token emitted, retry is fatal (consistency > availability)
- **Circuit breaker:** Consecutive failures trip breaker; half-open after cooldown

---

## 8. Agent Loop Architecture (Deep Dive)

### 8.1 ReAct Loop with Full Observability

```
User Input
    │
    ▼
[1] Load Checkpoint → restore state or start fresh
    │
    ▼
[2] Context Compaction → if token usage > threshold
    │
    ▼
[3] Input Guardrails → Tier 1/2/3 evaluation
    │
    ▼
[4] beforeRound Middleware → parallel execution, prioritized
    │
    ▼
[5] Rebuild Tools + System Prompt → dynamic routing
    │
    ▼
[6] LLM Stream → per-chunk timeout, retry, backpressure
    │     ├── token → yield StreamToken
    │     ├── reasoning → yield ThinkingToken
    │     └── tool_call → extract and queue
    │
    ▼
[7] Tool Execution → parallel via StructuredTaskScope
    │     ├── beforeTool middleware (cancel/substitute)
    │     ├── Tool guardrails
    │     ├── Execute with retry + sandbox
    │     └── afterTool middleware (transform results)
    │
    ▼
[8] Output Guardrails → validate against output schema
    │
    ▼
[9] Save Checkpoint → async flush
    │
    ▼
[10] Yield Done or Loop to [2]
```

### 8.2 Middleware System

Six hooks, executed in priority order:

```java
public interface Middleware {
    int priority(); // lower = earlier
    
    default Result<String, MiddlewareError> beforeRound(
        String runId, List<Message> history, Map<String, Object> context) {
        return Result.ok("");
    }
    
    default Result<CompactionResult, MiddlewareError> maybeCompact(
        String runId, List<Message> history, TokenCount current, TokenCount max) {
        return Result.ok(null); // null = no compaction
    }
    
    default Result<ToolDecision, MiddlewareError> beforeTool(
        String runId, String toolName, Map<String, Object> args, Map<String, Object> context) {
        return Result.ok(new ToolDecision(true, args, null));
    }
    
    // ... extraSystemPrompt, extraTools, afterTool, afterRound
}
```

**Built-in Middlewares:**
- `SummarizationMiddleware` — Compacts at 85% threshold using LLM summarization
- `LargeOutputOffloadMiddleware` — Offloads tool outputs > 8KB to disk
- `TokenBudgetMiddleware` — Enforces per-run token budgets
- `ObservabilityMiddleware` — Emits structured JSONL logs
- `TodoEnforcementMiddleware` — Requires structured task planning

### 8.3 Human-in-the-Loop

```java
public class HitlGate {
    public Result<HitlDecision, HitlError> requestApproval(
        String runId, String toolName, Map<String, Object> args, Duration timeout);
    
    public boolean approve(String gateId);
    public boolean approveSession(String gateId); // approve all future
    public boolean reject(String gateId, String reason);
}
```

- Gates are serializable to checkpoints
- Survive process restarts
- Default sensitive tools: file_write, file_edit, shell_exec, git_commit, delegate
- Timeout defaults to 5 minutes; configurable per-tool

---

## 9. StateGraph Architecture (Deep Dive)

### 9.1 Graph Construction

```java
StateGraph<AppState> graph = StateGraph.<AppState>builder(AppState.class)
    .addNode("research", (state, config) -> researchAgent.run(state.query()))
    .addNode("write", (state, config) -> writerAgent.run(state.research()))
    .addNode("review", (state, config) -> reviewerAgent.run(state.draft()))
    .addEdge(START, "research")
    .addEdge("research", "write")
    .addConditionalEdge("write", 
        state -> state.quality() > 0.8 ? "end" : "review",
        Map.of("end", END, "review", "review"))
    .addEdge("review", "write")
    .build();
```

### 9.2 Channels & Reducers

```java
public interface Channel<T> {
    T reduce(T existing, T update);
}

Channel<String> lastValue = (a, b) -> b;
Channel<List<String>> append = (a, b) -> Stream.concat(a.stream(), b.stream()).toList();
Channel<Set<String>> setUnion = (a, b) -> { Set<String> s = new HashSet<>(a); s.addAll(b); return s; };
Channel<Integer> sum = Integer::sum;
```

### 9.3 Execution Engine

```java
public class CompiledGraph<S> {
    public Publisher<GraphEvent> invoke(S initialState, Map<String, Object> inputs);
}
```

- **Wave execution:** All runnable nodes execute in parallel via StructuredTaskScope
- **State fingerprinting:** Detect infinite loops by hashing state after each wave
- **Checkpointing:** Save state after every wave to `Checkpointer`
- **Deadlock detection:** If finish points are unreachable, fail fast
- **Time travel:** Resume from any checkpoint with modified state

---

## 10. Multi-Agent Swarm Architecture (Deep Dive)

### 10.1 Orchestration Topologies

| Topology | Use Case | Implementation |
|----------|----------|----------------|
| **Handoff** | Sequential agent transfer | `handoff_to_agent` tool; context filtering |
| **DAG** | Parallel research → synthesis | Topological sort + wave execution |
| **Supervisor** | Complex task decomposition | Central router delegates to specialists |
| **Debate** | Quality assurance | Round-robin with convergence strategies |

### 10.2 Context Isolation

Each agent in a swarm receives:
- Its own system prompt
- Its own tool subset
- Its own token budget
- Filtered conversation history (only relevant messages)

### 10.3 Circuit Breaker

```java
public class SwarmCircuitBreaker {
    private final int maxConsecutiveSameAgent;
    private final long maxTokensPerAgent;
    private final double maxCostUsd;
    
    public Result<Void, BudgetError> canExecute(String agentName, TokenCount usage);
}
```

- Prevents infinite handoff loops
- Enforces per-agent and total swarm budgets
- Emits `BudgetExceededEvent` for upstream handling

---

## 11. Guardrails Architecture (Deep Dive)

### 11.1 Three-Tier Defense

```
User Input
    │
    ├──→ Tier 1 (Fast) ──→ Regex/Keyword/Hash ──→ <1ms
    │       └── BlockList, PromptInjectionDetector, DangerousCommandDetector
    │
    ├──→ Tier 2 (ML) ──→ ONNX NER / Embedding Similarity ──→ 20-100ms
    │       └── PiiDetector, ToxicityClassifier, TopicFilter
    │
    └──→ Tier 3 (LLM) ──→ LLM-as-Judge ──→ 500ms-8s
            └── PolicyEvaluation, OutputQualityCheck
```

### 11.2 Adaptive Thresholds

```java
public class AdaptiveThreshold {
    private final double initialThreshold;
    private final double learningRate;
    
    public void update(boolean wasFalsePositive);
    public double currentThreshold();
}
```

- Self-improving from operator feedback
- Tracks false positive/negative rates
- Adjusts per-tier latency budgets

---

## 12. Testing Strategy

### 12.1 Unit Tests

- **Result combinators:** 100% branch coverage
- **Tokenizer:** Cross-validated against Python tiktoken on 10K strings
- **BPE merge logic:** Property-based testing (quickcheck-style)
- **Graph cycle detection:** All cyclic graphs rejected, all DAGs accepted

### 12.2 Integration Tests

- **Agent loop:** Mock LLM client with predetermined responses
- **End-to-end:** Real API calls (nightly, not per-PR)
- **Checkpoint recovery:** Kill JVM mid-execution, verify recovery
- **Concurrent safety:** 1000 parallel agents, no race conditions

### 12.3 Performance Tests

- **Token throughput:** > 10K tokens/sec parsing
- **Agent latency:** P99 < 2s for single-round agent
- **Memory:** < 100MB heap for 100 concurrent agents
- **Streaming:** No token drops at 100 tokens/sec

---

## 13. Technology Stack

| Layer | Technology | Version | Rationale |
|-------|-----------|---------|-----------|
| Language | Java | 25 LTS | Scoped Values, Structured Concurrency, Pattern Matching |
| Build | Gradle | 8.11 | Kotlin DSL, incremental builds |
| JSON | Jackson | 2.18 | Streaming API for SSE parsing |
| HTTP | JDK HttpClient | 11+ | HTTP/2, no external dependency |
| Cache | Caffeine | 3.1.8 | High-performance, bounded caches |
| Metrics | Micrometer | 1.14 | Vendor-neutral metrics |
| Tracing | OpenTelemetry | 1.44 | OTLP export, auto-instrumentation |
| Null Safety | JSpecify | 1.0.0 | `@NullMarked`, `@NonNull`, `@Nullable` |
| Testing | JUnit 5 | 5.11 | Parameterized tests, parallel execution |
| Optional | Spring Boot | 4.0 | Only in `chorus-engine-spring` module |

**Explicitly NOT Used:**
- Spring AI (couples to Spring ecosystem)
- LangChain4j (bridges Python abstractions)
- Netty (unnecessary for HTTP/2 client)
- Project Reactor (JDK Flow is sufficient)

---

## 14. Open Questions

1. **Should we support Groovy/Kotlin DSLs for graph definition?** — Defer to Phase 5.
2. **Should we implement a local embedding model?** — Yes, ONNX Runtime MiniLM in `skills` module.
3. **Should we support function calling via JSON Schema or XML?** — Both. JSON Schema for OpenAI-compatible, XML for Claude.
4. **How do we handle reasoning models (o3, DeepSeek-R1)?** — Separate `<think>` content extraction middleware.

---

## 15. Success Metrics

| Metric | Target |
|--------|--------|
| Lines of code (framework) | < 50K |
| External dependencies (core) | < 5 |
| Startup time (agent) | < 50ms |
| Token counting accuracy | ±1% vs reference |
| Concurrent agents per JVM | > 1000 |
| Time to first token (streaming) | < 500ms |
| Documentation coverage | 100% public API |
| Test coverage | > 80% branches |

---

*Plan authored by Chorus Engine Architecture Team. For questions, refer to ADR-001 through ADR-015 in `/docs/adrs/`.*
