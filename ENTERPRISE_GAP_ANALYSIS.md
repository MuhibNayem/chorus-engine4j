# Chorus Engine Java — Enterprise Gap Analysis & 2026 SOTA Roadmap

**Date:** 2026-05-18  
**Auditor:** Kimi Code CLI  
**Scope:** Full codebase audit against 2026 production-grade AI agent frameworks

---

## 1. Honest Verdict: Is This Enterprise-Ready?

**No. Not yet.**

The current `chorus-engine-java` is a **solid architectural foundation** with good bones — the module separation, event-driven design, checkpointing, and graph execution are well-conceived. But it is currently at **~35% of what a production enterprise agent framework needs in 2026**.

### What Works Today
| Component | Status | Quality |
|-----------|--------|---------|
| `AgentLoop` (ReAct) | ✅ Implemented | Good — middleware, HITL, BTW queue, checkpoint save/load |
| `StateGraph` / `CompiledGraph` | ✅ Implemented | Good — parallel wave execution, channel reducers, conditional edges |
| `HitlGate` | ✅ Implemented | Good — timeout, disposal, session approvals |
| `JsonFileCheckpointer` | ✅ Implemented | Good — atomic tmp+rename |
| Event system (`AgentEvent`) | ✅ Implemented | Excellent — 30+ sealed event types |
| Basic tools (filesystem, shell, git) | ✅ Implemented | Minimal — no safety sandboxing |
| Tiered guardrails (skeleton) | ⚠️ Stub | Has structure, no real NER/embedding/LLM judge |
| Spring Boot Starter | ⚠️ Minimal | Auto-configures checkpointer + tools only |
| **Spring AI integration** | ❌ **Missing** | `ChorusChatModel` is an interface with **zero adapters** |
| **MCP protocol** | ❌ **Missing** | Not implemented — the 2026 tool standard |
| **A2A protocol** | ❌ **Missing** | Not implemented — the 2026 agent standard |
| **Telemetry/Observability** | ❌ **Empty module** | `chorus-telemetry` has **zero files** |
| **Semantic Router / Harness** | ❌ **Empty module** | `chorus-harness` has **zero files** |
| **Evaluations** | ❌ **Missing** | No eval framework, no regression testing |
| **Streaming from LLM** | ❌ **Not wired** | `StreamingResponse` defined but unused |
| **Vector store / Memory** | ❌ **Missing** | No RAG, no long-term memory |

---

## 2. What the 2026 Cutting Edge Looks Like

Based on research across LangGraph (126K★), CrewAI, Microsoft Agent Framework, Google ADK, Anthropic Claude SDK, and the protocol ecosystem:

### 2.1 The Three Protocol Layers (Industry Standard)
```
┌─────────────────────────────────────────┐
│  Orchestration (LangGraph / Chorus)     │  ← You are here
│  — decides WHEN and HOW to invoke       │
├─────────────────────────────────────────┤
│  Peer Protocol: A2A                     │  ← Missing
│  — agent-to-agent task delegation       │
│  — Agent Cards for dynamic discovery    │
├─────────────────────────────────────────┤
│  Tool Protocol: MCP                     │  ← Missing
│  — 97M+ monthly SDK downloads           │
│  — standardized tool schema exposure    │
│  — streaming-first, resource-aware      │
└─────────────────────────────────────────┘
```

### 2.2 The 2026 Feature Matrix (What Enterprises Demand)

| Capability | 2026 Standard | Chorus Status |
|------------|--------------|---------------|
| **MCP Client/Server** | Table stakes for tool access | ❌ Not started |
| **A2A Agent Cards** | Cross-vendor agent delegation | ❌ Not started |
| **Structured Output (Constrained Decoding)** | Native JSON schema enforcement, streaming | ⚠️ Basic JSON validation only |
| **OpenTelemetry GenAI Tracing** | Standardized observability | ❌ Empty module |
| **3-Layer Evals** | Unit + LLM-as-judge + production sampling | ❌ Missing |
| **Time-Travel Debugging** | Rewind to any checkpoint, edit, replay | ⚠️ Checkpoints exist, no rewind UI/API |
| **Context Engineering** | Token budgets, dynamic compaction, limit enforcement | ⚠️ Interface exists, no implementations |
| **Graph Visualization** | PlantUML/Mermaid export, real-time render | ❌ Missing |
| **Voice/Multimodal** | WebSocket voice, image input, computer use | ❌ Missing |
| **Sub-agent Isolation** | Isolated context windows for delegation | ⚠️ Handoff exists, no true isolation |
| **Durable Execution** | Auto-retry, recovery, long-running with timeout | ⚠️ Partial — no retry at LLM level |
| **Semantic Router** | Intent-based routing between agents/skills | ❌ Empty module |
| **Prompt Management** | Versioning, hub, A/B testing | ❌ Missing |
| **Cost Tracking** | Per-run cost attribution, budget caps | ⚠️ Basic estimate method exists |
| **Computer Use** | Screenshot → action loop for desktop/browser | ❌ Missing |
| **Agent Skills / Adaptive Learning** | Dynamic skill acquisition, skill registry | ❌ Missing |
| **Streaming Tokens** | Per-token events from LLM to client | ❌ Not wired |

---

## 3. The 10 Critical Gaps (Ranked by Impact)

### 🔴 P0 — Framework Won't Run Without These

#### Gap 1: Zero Spring AI / LLM Adapter
**Problem:** `ChorusChatModel` is an interface. No adapter bridges it to Spring AI `ChatClient`, `ChatModel`, or any provider (OpenAI, Anthropic, Gemini, Ollama). The `AgentLoop` cannot actually call an LLM.

**2026 Standard:** Spring AI Alibaba, Microsoft Semantic Kernel, and LangChain all provide native multi-provider adapters with automatic tool schema generation.

**Fix:** Build `SpringAiChatModelAdapter` that implements `ChorusChatModel` using Spring AI's `ChatClient`. Support tool calling via Spring AI's `FunctionCallback`. Support streaming via `Flux<ChatResponse>`.

---

#### Gap 2: MCP Protocol Support
**Problem:** Tools are hardcoded Java classes. In 2026, tools are exposed via MCP servers — external processes that advertise their schema via JSON-RPC. This enables polyglot tool ecosystems (Python tools called from Java agents).

**2026 Standard:** 97M+ monthly downloads. Claude Desktop, VS Code, Cursor, and every major framework use MCP. The spec is governed by the Linux Foundation.

**Fix:** Implement `McpClient` (stdio + SSE transports) and `McpToolAdapter` that exposes MCP tools as `AgentTool` instances. Support resource subscriptions, roots, and sampling.

---

#### Gap 3: A2A Protocol Support
**Problem:** No agent-to-agent communication. Swarm handoffs are in-process only.

**2026 Standard:** Google's A2A protocol (50+ enterprise partners, Linux Foundation) defines Agent Cards, task delegation, and artifact exchange. CrewAI and Google ADK have native A2A.

**Fix:** Implement `A2aClient` for task delegation, `AgentCard` publishing, and `A2aServer` for receiving tasks. This enables distributed multi-agent systems.

---

### 🟠 P1 — Required for Production

#### Gap 4: Telemetry & Observability (Empty Module)
**Problem:** `chorus-telemetry` has zero files. No tracing, no metrics, no cost attribution.

**2026 Standard:** OpenTelemetry GenAI semantic conventions are converging. LangSmith, Langfuse, Braintrust, and Arize all expect standardized traces. Enterprises need per-tenant cost attribution.

**Fix:** Implement OTel instrumentation for:
- Span per agent round (input/output tokens, latency, model name)
- Span per tool call (duration, success/failure)
- Span per graph node execution
- Cost attribution tags
- Export to Langfuse/Braintrust-compatible format

---

#### Gap 5: Semantic Router / Harness (Empty Module)
**Problem:** `chorus-harness` has zero files. No intent classification, no dynamic routing.

**2026 Standard:** Semantic routing is how multi-agent systems decide which agent handles a request. LangGraph has `create_react_agent`, CrewAI has role-based routing.

**Fix:** Build `SemanticRouter` using embeddings (Spring AI `EmbeddingClient`) + vector store. Support route registration with example utterances, threshold-based fallback, and cached embeddings.

---

#### Gap 6: Evaluation Framework
**Problem:** No evals. The 16 tests are unit tests for graph mechanics, not agent quality evals.

**2026 Standard:** "80% of AI pilots fail to scale due to missing eval infrastructure." The 3-layer model is:
1. **Unit evals** — schema validation, routing correctness (CI, cheap)
2. **LLM-as-judge** — rubric-based quality scoring (per-PR)
3. **Production sampling** — drift detection on real traffic (continuous)

**Fix:** Build `EvalHarness` with:
- Dataset management (golden conversations)
- Scoring functions (exact match, embedding similarity, LLM-as-judge)
- CI integration (JUnit-style eval tests)
- Regression detection (compare scores across commits)

---

#### Gap 7: Structured Output with Constrained Decoding
**Problem:** The `AgentLoop` does basic JSON validation *after* generation. If invalid, it adds a correction prompt and retries. This wastes tokens and adds latency.

**2026 Standard:** All major providers support native structured output via constrained decoding (OpenAI `json_schema`, Anthropic `output_config.format`, Gemini `response_json_schema`). The model *cannot* emit invalid JSON by construction.

**Fix:** Add `outputSchema` support to `ChorusChatModel` that uses provider-native structured output modes. Fall back to tool-based schema enforcement for providers without native support.

---

#### Gap 8: Streaming from LLM
**Problem:** `StreamingResponse` is defined but never used. The `AgentLoop` calls `generate()` which blocks until the full response arrives.

**2026 Standard:** Per-token streaming is expected. Clients want to see tokens as they generate, not after a 10-second wait. LangGraph supports per-node token streaming.

**Fix:** Wire `ChorusChatModel.stream()` through Spring AI's streaming API. Emit `AgentEvent.TokenEvent` for each token. Support reasoning content streaming (Claude's extended thinking).

---

### 🟡 P2 — Required for SOTA DX

#### Gap 9: Context Engineering & Token Management
**Problem:** The `AgentMiddleware.maybeCompact()` interface exists but has no implementations. No token counting, no budget enforcement.

**2026 Standard:** Context engineering is a first-class concern. Spring AI Alibaba has "context editing, model & tool call limit, dynamic tool selection." LangGraph has summarization middleware.

**Fix:** Implement:
- `TokenBudgetMiddleware` — tracks cumulative tokens, triggers compaction at threshold
- `SummarizationMiddleware` — uses cheap model to summarize old messages
- `DynamicToolSelectionMiddleware` — selects relevant tools via embeddings instead of sending all

---

#### Gap 10: Graph Visualization & Time-Travel
**Problem:** Checkpoints save state but there's no API to list checkpoints, rewind, or replay.

**2026 Standard:** LangGraph Studio provides real-time graph visualization and time-travel debugging. This is "the most-envied debugging feature in the space."

**Fix:** Add REST endpoints to:
- List checkpoints for a thread
- Fork a thread from a specific checkpoint
- Replay execution from a checkpoint with modified state
- Export graph to Mermaid/PlantUML

---

## 4. Recommended Implementation Roadmap

### Phase 1: Make It Actually Run (2–3 weeks)
1. **Spring AI Adapter** — `SpringAiChatModelAdapter` bridging `ChorusChatModel` to `ChatClient`
2. **Tool registration** — Auto-discover `@Component` tools and register with Spring AI
3. **Streaming** — Wire `ChatClient.stream()` to `AgentEvent.TokenEvent`
4. **Integration test** — End-to-end test with a real LLM call (use Ollama for local testing)

### Phase 2: Protocol Layer (3–4 weeks)
5. **MCP Client** — Stdio + SSE transports, tool discovery, `McpToolAdapter`
6. **A2A Client/Server** — Agent Cards, task delegation, artifact exchange
7. **Structured Output** — Provider-native schema enforcement

### Phase 3: Production Hardening (3–4 weeks)
8. **Telemetry module** — OTel instrumentation, trace export, cost attribution
9. **Semantic Router** — Embedding-based intent classification, vector store integration
10. **Guardrails** — Real NER (via OpenNLP/Stanford NLP), embedding-based toxicity, LLM judge
11. **Evals harness** — Golden datasets, LLM-as-judge, CI integration

### Phase 4: SOTA DX (4–6 weeks)
12. **Context engineering** — Token budgets, summarization, dynamic tool selection
13. **Graph visualization** — Mermaid/PlantUML export, checkpoint browsing API
14. **Voice/Multimodal** — WebSocket voice agent, image input support
15. **Computer Use** — Screenshot → action loop (browser automation via Playwright)
16. **Agent Skills** — Dynamic skill registry, skill discovery via embeddings

---

## 5. Competitive Positioning

| Framework | Lang | Strengths | Where Chorus Can Win |
|-----------|------|-----------|---------------------|
| **LangGraph** | Python/JS | Mature, time-travel, 126K★ | Java ecosystem has NO equivalent. Be the LangGraph for JVM. |
| **Spring AI Alibaba** | Java | Visual builder, A2A, MCP | Less flexible graph primitives. Chorus has better graph engine. |
| **CrewAI** | Python | Fast prototyping, role-based | No Java version. Chorus can capture Java enterprise market. |
| **Microsoft Agent Framework** | .NET/Python | Azure integration | Tied to Microsoft. Chorus is vendor-neutral. |
| **Google ADK** | Python/Java/Go | A2A native, multimodal | Early stage. Chorus can be more mature for Java. |

**The Opportunity:** There is no production-grade, graph-based agent orchestration framework for Java in 2026. Spring AI Alibaba is the closest but focuses on visual builders and pre-built patterns. If Chorus delivers:
- Full LangGraph parity (state graphs, checkpointing, HITL, streaming)
- Native MCP + A2A
- Spring Boot-native DX
- OTel observability

…it becomes the **de facto standard for Java agent orchestration**.

---

## 6. Immediate Next Steps

1. **Do not add more modules** until Spring AI integration works end-to-end.
2. **Implement `SpringAiChatModelAdapter`** — this is the single most important file.
3. **Add an integration test** that calls Ollama (local LLM) and verifies a full ReAct loop.
4. **Once the loop runs**, implement MCP client support — this unlocks the polyglot tool ecosystem.
5. **Then** tackle telemetry and evals — these are what make it production-grade, not demo-grade.

---

*This framework has excellent architectural bones. The gap is implementation depth, not vision. Focus on making the core loop run with a real LLM, then layer the 2026 protocols and observability on top.*
