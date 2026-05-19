# Chorus Engine Java — Implementation Roadmap

## Research Synthesis (May 2026)

### Multi-Agent Orchestration (2026 State of Art)
- **Three dominant patterns**: Supervisor/Hierarchical, Swarm (dynamic handoffs), Planner-Executor
- **LangGraph** leads with Pregel-inspired StateGraph: channels, reducers, checkpointing, cycles, branching
- **OpenAI Agents SDK** (2026): handoffs + guardrails as first-class primitives
- **Production requirement**: deterministic orchestration > emergent behavior. Circuit breakers, iteration caps, timeout thresholds mandatory
- **Cost routing**: cheap models for triage (GPT-5.4-mini), frontier models for reasoning
- **Failure modes**: 41-86.7% failure rate in production MAS — 79% are coordination/spec issues, not model capability

### Graph/Workflow (2026 State of Art)
- **LangGraph PregelLoop**: stateful graph execution with parallel branches
- **Key primitives**: Nodes, Edges, Conditional Edges, State Reducers, Checkpoints
- **Persistence**: PostgreSQL or Redis checkpointer — survive process restarts, resume days later
- **Human-in-loop**: `interrupt_before` / `interrupt_after` breakpoints
- **Streaming**: per-node state updates + token streaming

### MCP (Model Context Protocol) — 2026
- **97M monthly SDK downloads**, Linux Foundation governed (AAIF)
- **Primitives**: Tools, Resources, Prompts
- **Transport**: Streamable HTTP (replacing HTTP+SSE), stdio for local
- **Auth**: OAuth 2.1 with Resource Indicators (RFC 8707)
- **Security**: capability attestation, scoped tokens, tool annotations (read-only vs destructive)
- **Spec**: 2025-11-25 revision with JSON-RPC batching

### A2A (Agent-to-Agent) — v1.0 April 2026
- **Google + 150+ orgs**, Linux Foundation project
- **Agent Cards**: `/.well-known/agent.json` — capability discovery with JWS signing
- **Tasks**: lifecycle `submitted → working → completed|failed|canceled|input_required`
- **Transport**: HTTP + JSON-RPC 2.0 + SSE for streaming
- **Auth**: OAuth 2.1 with PKCE
- **Complementary to MCP**: MCP = agent→tool, A2A = agent→agent

### Vector Stores 2026
- **pgvector**: default for <10M vectors, SQL-native filtering, ACID, zero moving parts
- **Qdrant**: Rust-native, ~1840 QPS at 1M vectors, best performance/cost
- **Pinecone**: fully managed, fastest time-to-production, no self-host
- **Weaviate**: strongest hybrid search (BM25 + vector), multi-tenant namespaces
- **Milvus**: billion-scale, GPU-accelerated, distributed by design
- **All use HNSW** under the hood. Metadata pre-filtering is mandatory for production.

---

## Phase 1: Foundation for Production (Critical Path)

### 1.1 Tools Module
Agents cannot interact with the world without tools. Every major framework (MCP, LangChain, OpenAI SDK) has standardized tool interfaces.

**Implement:**
- `Tool` interface: name, description, parameters schema, execute(args)
- `ToolRegistry`: discovery, capability matching, safety validation
- `FilesystemTool`: read, write, list, glob, safe path sandboxing
- `ShellTool`: command execution with timeout, allowlist, sandbox
- `GitTool`: status, diff, log, branch, commit (read-only by default)
- `WebSearchTool`: search + fetch with rate limiting
- `SafetyValidator`: prevents dangerous commands before execution

### 1.2 Persistent Checkpointer
The current `InMemoryCheckpointer` loses all state on restart. Production requires durable state.

**Implement:**
- `JdbcCheckpointer`: PostgreSQL/MySQL via JDBC — transactional, ACID
- `RedisCheckpointer`: Redis with TTL, pub/sub for distributed coordination
- `CheckpointSerializer`: JSON/BSON/MessagePack for state serialization
- Migration support: schema versioning, backward compatibility

### 1.3 Vector Store Adapters
The RAG module has only `InMemoryVectorStore`. Production needs persistent adapters.

**Implement:**
- `PgVectorStore`: PostgreSQL + pgvector extension — SQL filtering, HNSW index
- `QdrantVectorStore`: HTTP/gRPC client for Qdrant — payload filtering, hybrid search
- `PineconeVectorStore`: REST client for Pinecone — metadata filtering, sparse-dense
- `MilvusVectorStore`: REST client for Milvus — partition-based multi-tenancy

---

## Phase 2: Graph & Swarm (Agent Orchestration)

### 2.1 StateGraph Engine
A Pregel-inspired stateful graph execution engine — the heart of complex workflows.

**Implement:**
- `StateGraph<S>`: typed state graph with nodes, edges, conditional edges
- `Channel<T>`: state channels (LastValue, Topic, BinaryOp/reducer)
- `PregelExecutor`: parallel node execution, cycle detection, checkpointing
- `CompiledGraph`: compiled runnable with streaming, interrupt, resume
- `Node`: function that receives state, returns partial state updates
- `Edge`: unconditional transition
- `ConditionalEdge`: router function decides next node(s)

### 2.2 Swarm Orchestrator
Multi-agent coordination with supervisor, handoffs, and circuit breakers.

**Implement:**
- `Swarm`: container for agents with shared context
- `AgentDefinition`: name, instructions, tools, model, routing rules
- `SupervisorPattern`: central controller delegates to workers
- `HandoffProtocol`: transfer_to_agent with context passing
- `CircuitBreaker`: per-agent failure detection and fallback
- `CostRouter`: route simple tasks to cheap models
- `GroupChat`: round-robin or debate pattern with convergence detection
- `SwarmCheckpointer`: distributed session persistence

---

## Phase 3: Protocols & Observability

### 3.1 MCP (Model Context Protocol)
Standardized agent-to-tool protocol — 97M downloads, industry standard.

**Implement:**
- `McpClient`: connects to MCP servers, discovers tools/resources/prompts
- `McpServer`: exposes chorus-engine tools via MCP protocol
- `McpTransport`: stdio and streamable HTTP transports
- `McpSession`: JSON-RPC session with capability negotiation
- `ToolAdapter`: wraps chorus `Tool` as MCP tool
- OAuth 2.1 client support for authenticated servers

### 3.2 A2A (Agent-to-Agent Protocol)
Cross-vendor agent interoperability — v1.0, 150+ orgs.

**Implement:**
- `A2aClient`: discovers agents via Agent Cards, submits tasks
- `A2aServer`: exposes chorus agents via A2A protocol
- `AgentCard`: capability declaration with JWS signing
- `TaskManager`: task lifecycle (submitted→working→completed|failed)
- `A2aTransport`: HTTP + SSE for real-time updates
- `ArtifactExchange`: structured result passing between agents

### 3.3 Telemetry
Production observability via OpenTelemetry.

**Implement:**
- `TelemetryBridge`: OTel tracer + meter integration
- `AgentSpanExporter`: traces per agent execution
- `TokenUsageExporter`: cost tracking per call
- `LatencyHistogram`: p50/p95/p99 per provider/model
- `RedactionProcessor`: PII scrubbing before export

---

## Phase 4: Evals, Skills, Spring Boot

### 4.1 Evaluation Framework
Systematic agent evaluation — the difference between demo and production.

**Implement:**
- `EvalRunner`: execute test suites against agents
- `EvalScorer`: LLM-as-judge, exact match, semantic similarity
- `EvalDataset`: grounded Q&A pairs with expected answers
- `BenchmarkSuite`: standardized benchmarks (RAG, tool-use, reasoning)
- `RegressionDetector`: compare runs across versions

### 4.2 Skills Module
Reusable agent capabilities with semantic indexing.

**Implement:**
- `Skill`: packaged capability (prompt + tools + config)
- `SkillRegistry`: load, version, and resolve skills
- `SkillLoader`: load from filesystem, classpath, URL
- `SemanticIndex`: vector-based skill discovery
- `SkillRouter`: match user intent to best skill
- `SkillExecutor`: run skill with parameter binding

### 4.3 Spring Boot Starter
Enterprise Java deployment path.

**Implement:**
- `ChorusAutoConfiguration`: auto-wire all beans
- `ChorusProperties`: YAML-based configuration
- `AgentController`: REST endpoints for agent execution
- `RagController`: REST endpoints for RAG queries
- `SwarmController`: REST endpoints for swarm orchestration
- `HealthIndicator`: Spring Boot Actuator integration

---

## Phase 5: Additional LLM Providers

### 5.1 Provider Implementations
- `AnthropicProvider`: Claude with reasoning parser, extended thinking
- `GeminiProvider`: Google Gemini with multimodal support
- `VllmChatProvider`: vLLM/OpenAI-compatible chat completion
- `DeepSeekProvider`: DeepSeek with reasoning content

---

## Success Criteria

1. **All 17 modules compile and test green**
2. **Zero empty modules** — every module has production code
3. **Integration tests** cover end-to-end agent→tool→RAG→checkpoint workflows
4. **Spring Boot sample** runs a complete multi-agent RAG system
5. **Feature parity** with TypeScript original PLUS RAG, MCP, A2A advancements
