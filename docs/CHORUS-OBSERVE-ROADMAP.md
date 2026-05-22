# Chorus Observe — Implementation Roadmap

> **Research Date:** 2026-05-21
> **Scope:** Full design and phased implementation plan for Chorus Observe — a SOTA-beating, framework-agnostic LLM observability and evaluation platform built on top of Chorus Engine.
> **Positioning:** "Any harness connects freely." Universal OTLP-native ingestion + the only Java-first AI observability platform.

---

## 1. Market Analysis — What Every Platform Gets Wrong

### 1.1 Feature Matrix (2026 State of Art)

> **Scope note:** Features marked "Chorus runs only" require `chorus.*` span attributes and only work when the agent uses Chorus Engine. All other features work for any OTLP-compatible framework.
>
> **Resourcing reality:** Full platform (all 9 phases) requires 3-5 engineers over 7-9 months. The MVP (Phases 1-3) is achievable in ~3 months with 2 engineers.

| Feature | LangSmith | Langfuse | Arize Phoenix | Braintrust | Laminar | W&B Weave | **Chorus Observe** |
|---|---|---|---|---|---|---|---|
| Self-host (free) | ❌ Enterprise-only | ✅ | ✅ | ❌ Enterprise | ✅ | ❌ | ✅ Apache 2.0 |
| Open source license | ❌ Closed | ✅ MIT | ⚠️ Elastic 2.0 | ❌ | ✅ Apache 2.0 | ❌ | ✅ Apache 2.0 |
| Universal OTLP ingestion | ⚠️ Partial | ✅ | ✅ | ❌ | ❌ | ❌ | ✅ GenAI semconv |
| Java-native SDK | ⚠️ Wrapper | ❌ | ❌ | ❌ | ❌ | ❌ | ✅ First-class |
| Causal provenance DAG | ❌ | ❌ | ❌ | ❌ | ❌ | ❌ | ✅ Chorus runs only |
| Time-travel debugging | ⚠️ LangGraph only | ❌ | ❌ | ❌ | ❌ | ❌ | ✅ Chorus runs only |
| Eval inline in traces | ❌ Fragmented | ⚠️ | ❌ | ✅ | ❌ | ⚠️ | ✅ Native |
| Multi-turn red teaming | ❌ | ❌ | ❌ | ❌ | ❌ | ❌ | ✅ Built-in |
| SQL editor on trace data | ❌ | ❌ | ❌ | ❌ | ✅ | ❌ | ✅ |
| Budget enforcement UI | ❌ | ❌ | ❌ | ❌ | ❌ | ❌ | ✅ Live circuit breaker |
| Multi-framework in one trace | ❌ | ⚠️ | ⚠️ | ❌ | ❌ | ❌ | ✅ Any framework |
| Agent graph topology view | ✅ LangGraph only | ⚠️ Beta | ❌ | ❌ | ❌ | ❌ | ✅ Any topology |
| Multi-turn simulation | ❌ | ⚠️ | ⚠️ | ✅ | ❌ | ✅ | ✅ |
| Framework coupling | LangChain | None | Arize ecosystem | None | None | W&B | **None** |
| Pricing model | Per-seat $39/mo | Usage-based | OSS free | $249/mo Pro | Volume-based | Freemium | **Free self-host** |

### 1.2 Competitor Kill Shots — Where Each Loses

**LangSmith:**
- Enterprise-only self-host; steep per-trace pricing at scale
- Evaluation results are *not* surfaced inline with traces — requires custom navigation
- No multi-turn red teaming whatsoever
- Locked to LangChain ecosystem; "spotty support across diverse stacks"
- Polly (AI assistant) is the only innovation; core product has stagnated

**Langfuse:**
- Slow trace reading for 2,000+ span runs
- No SQL editor on trace data; no signal extraction
- Agent graph view is still beta
- OTLP backend works but GenAI semantic conventions support is partial

**Arize Phoenix:**
- Elastic 2.0 license (not Apache 2.0) — cannot fork or embed commercially
- Notebook-first UX borrowed from ML; not built for 24/7 production agent monitoring
- Single-machine deployment; no distributed mode in free tier
- No time-travel debugging

**Braintrust:**
- Eval-centric, not a debugger for production failures
- Closed source; enterprise-only self-hosting
- No Java SDK at all

**Laminar:**
- Best actual debugger but no Java support; limited multi-framework ingestion
- Volume-based pricing is punishing at high cardinality trace loads
- No red teaming

---

## 2. Chorus Observe — Strategic Positioning

### 2.1 The Core Thesis

Every current platform is built *around a specific framework* (LangSmith → LangChain, Arize → Python AI ecosystem) or *around a specific persona* (Braintrust → eval engineers, W&B → ML researchers).

**Chorus Observe is built around a protocol, not a framework.**

Any harness that speaks OTLP (OpenTelemetry Protocol) can connect. Any team — Python LangChain, Java Chorus, Go agents, CrewAI, AutoGen — sends the same wire format and sees the same UI. Zero proprietary SDK required.

### 2.2 Unfair Advantages

| Advantage | Why It's Unfair |
|---|---|
| **Java-native SDK** | No other AI observability platform has a first-class Java SDK. 30% of production AI is running on JVM. Zero competition. |
| **ProvenanceTracker → Causal DAG** | Chorus Engine records full decision causality as a DAG with typed `parent_ids` edges — not just a call hierarchy. Competitors (Phoenix, Laminar) show call trees; Chorus Observe shows *why* each decision was made by linking it to the decision that caused it. The visualization class is new; the DAG-with-causality data model is unique. |
| **EventBus → Zero-config tracing** | Chorus Engine apps get full tracing with zero code changes — the EventBus already emits everything. Other platforms require SDK instrumentation everywhere. |
| **Time-travel debugging for any agent** | Built on Chorus Engine's checkpointing, which works for *any* agent topology — not just LangGraph's StateGraph. |
| **Apache 2.0 + free self-host** | The only platform where self-hosting includes every feature. LangSmith, Braintrust, W&B all paywall self-hosting. This alone will capture the open-source community. |
| **OTel GenAI native** | The OTel GenAI semantic conventions (`gen_ai.*`) just stabilized in 2026. Chorus Observe is designed from day one around this standard — first-mover advantage. |
| **Eval-trace unification** | Eval results appear *inline* in the trace waterfall — not in a separate tab. No context switching. LangSmith explicitly lists this as a known gap. |
| **Budget circuit breaker UI** | Visual live enforcement of token/cost budgets via `BudgetEnforcer`. Unique in the market. |

### 2.3 Architecture Overview

```
┌─────────────────────────────────────────────────────────────────────────────────────┐
│                              Any Agent Framework                                     │
│                                                                                      │
│  Chorus Engine (Java)    LangChain (Python)    CrewAI    AutoGen    Custom Go Agent  │
│        │                       │                  │         │              │          │
│        │ EventBus→OTLP          │ OTel SDK          │ OTel     │ OTel        │ OTLP    │
└────────┼───────────────────────┼──────────────────┼─────────┼─────────────┼─────────┘
         │                       │                  │         │              │
         └───────────────────────┴──────────────────┴─────────┴──────────────┘
                                                │
                                   ChorusWire Protocol
                               (OTLP/gRPC + HTTP/JSON)
                                                │
                         ┌──────────────────────▼──────────────────────┐
                         │              Chorus Observe Backend          │
                         │                                              │
                         │  ┌──────────────┐   ┌────────────────────┐  │
                         │  │  OTLP Intake │   │   REST API v1      │  │
                         │  │  (gRPC 4317) │   │   /api/runs        │  │
                         │  │  (HTTP 4318) │   │   /api/traces      │  │
                         │  └──────┬───────┘   │   /api/datasets    │  │
                         │         │            │   /api/evals       │  │
                         │  ┌──────▼───────┐   └────────────────────┘  │
                         │  │  Trace Store │                            │
                         │  │  PostgreSQL  │   ┌────────────────────┐  │
                         │  │ +TimescaleDB │   │  Eval Engine       │  │
                         │  │  +pgvector   │   │  (chorus-evals)    │  │
                         │  └──────────────┘   └────────────────────┘  │
                         └──────────────────────────────────────────────┘
                                                │
                         ┌──────────────────────▼──────────────────────┐
                         │              Chorus Studio (UI)              │
                         │                                              │
                         │  Next.js + React + Tailwind                 │
                         │  Trace Waterfall  │  Provenance DAG         │
                         │  Eval Dashboard   │  Time-Travel Debugger   │
                         │  Red Team Studio  │  Prompt Playground      │
                         │  Budget Monitor   │  SQL Query Editor       │
                         └──────────────────────────────────────────────┘
```

---

## 3. Data Model

```sql
-- Core run/trace hierarchy
runs          (run_id, framework, agent_id, model, start_time, end_time, status, tags, metadata)
spans         (span_id, run_id, parent_span_id, span_name, kind, start_time, end_time, attributes, events)
llm_calls     (call_id, span_id, run_id, provider, model, input_tokens, output_tokens, cost_usd, latency_ms, prompt, completion)
tool_calls    (call_id, span_id, run_id, tool_name, args, result, latency_ms, error)
rag_queries   (query_id, span_id, run_id, query, retrieved_chunks, similarity_scores, latency_ms)

-- Provenance (Chorus Engine exclusive)
provenance_entries  (entry_id, run_id, agent_id, decision_type, input_state, reasoning, output, parent_ids, timestamp)

-- Evaluation
datasets      (dataset_id, name, description, created_at, tags)
dataset_items (item_id, dataset_id, input, expected_output, metadata)
eval_runs     (eval_run_id, dataset_id, agent_config, started_at, finished_at, summary_metrics)
eval_results  (result_id, eval_run_id, item_id, span_id, actual_output, scores, pass_fail)

-- Prompts
prompt_versions (version_id, name, content, model, temperature, created_at, created_by)
prompt_tags     (version_id, tag_name)  -- "production", "staging", "v2.1"

-- Feedback
feedback        (feedback_id, run_id, span_id, score, label, comment, source, created_at)

-- Monitoring
metric_snapshots (snapshot_id, metric_name, value, tags, timestamp)  -- TimescaleDB hypertable
alert_rules      (rule_id, condition, threshold, webhook_url, enabled)
alert_events     (event_id, rule_id, triggered_at, value, resolved_at)
```

---

## 4. Implementation Phases

---

### Phase 1: ChorusWire Protocol + Persistence Foundation

**Goal:** Any OTLP-compatible agent framework can send traces. Zero-config for Chorus Engine apps.

**Estimated effort:** 3-4 weeks (2 engineers)

#### 1.1 ChorusWire = OTel Collector + Custom Chorus Exporter

Rather than building a custom gRPC ingestion server (which would reinvent OTel Collector's battle-tested backpressure, batching, retry semantics, and mTLS), the ingestion layer is:

```
Any Agent Framework
        │  (OTLP/gRPC 4317 or OTLP/HTTP 4318)
        ▼
  OTel Collector (Apache 2.0)
    ├── receivers:   otlp (gRPC + HTTP) — standard, no custom code
    ├── processors:  chorus_attributes_processor  ← custom: normalize gen_ai.* + chorus.* attrs
    │                batch_processor              ← standard OTel
    │                tail_sampling_processor      ← standard OTel
    └── exporters:   chorus_postgres_exporter     ← custom: writes to PostgreSQL/ClickHouse
                     chorus_events_exporter       ← custom: writes ChorusEvent batch JSON
```

The "ChorusWire Protocol" **is** the OTel GenAI semantic convention spec plus Chorus-specific attributes:

```
# Standard OTel GenAI attributes (2026 spec, stable)
gen_ai.system                   # "openai", "anthropic", "chorus"
gen_ai.request.model            # "gpt-4o", "claude-opus-4-7"
gen_ai.usage.input_tokens       # integer
gen_ai.usage.output_tokens      # integer
gen_ai.response.finish_reasons  # ["stop", "tool_calls"]
gen_ai.agent.id                 # agent identifier
gen_ai.tool.name                # tool invoked

# Chorus-specific extension attributes (voluntary; enriches Chorus-only features)
chorus.run_id                   # run correlation ID
chorus.provenance_chain         # JSON causal DAG from ProvenanceTracker
chorus.checkpoint_id            # enables time-travel debugging
chorus.guardrail_tier           # 1/2/3 — which tier fired
```

Any framework that ships OTel auto-instrumentation (LangChain, LlamaIndex, OpenAI SDK, etc.) connects via standard OTLP. Chorus-specific features (provenance DAG, time-travel) only activate when `chorus.*` attributes are present — they degrade gracefully for non-Chorus runs.

#### 1.2 Chorus Engine → OTLP Exporter

Add `ChorusOtlpExporter` to `chorus-engine-telemetry`. Subscribes to `EventBus` and re-emits as OTLP spans with full GenAI semantic convention attributes.

```java
// chorus.yml — zero-code activation
chorus:
  observe:
    endpoint: "http://localhost:4317"  # ChorusWire gRPC endpoint
    export-provenance: true            # Include causal DAG in spans
    sample-rate: 1.0
```

Zero code changes in user applications. The Spring Boot starter auto-wires the exporter.

#### 1.3 Trace Sampling Strategy

Production agent systems generate millions of spans/day. Unsampled ingestion is neither affordable nor necessary. Three sampling modes, configurable per-deployment:

**Head-based sampling (default: 100% for dev, 10% for prod):**
Configured in OTel Collector's `probabilistic_sampler` processor. Simple, low-overhead, but may miss rare failures.

**Tail-based sampling (recommended for prod):**
OTel Collector's `tail_sampling` processor buffers spans for 30s and applies policies:
```yaml
# Always keep: errors, high-cost runs, slow runs, red-team results
policies:
  - name: always-errors
    type: status_code
    status_code: {status_codes: [ERROR]}
  - name: high-cost
    type: numeric_attribute
    numeric_attribute: {key: "chorus.cost_usd", min_value: 0.10}
  - name: latency-spike
    type: latency
    latency: {threshold_ms: 10000}
  - name: base-rate
    type: probabilistic
    probabilistic: {sampling_percentage: 10}
```

**Per-tenant override:**
Each API key can carry a `X-Chorus-Sample-Rate: 1.0` header to force 100% sampling for debugging sessions.

Sampled-out spans still contribute to cost/token metrics via OTel Collector's `metrics_transform` processor — metrics are never sampled.

#### 1.4 Persistence Layer

Two storage tiers, each chosen for what it's best at:

| Data type | Store | Justification |
|---|---|---|
| **Span/trace data** | **ClickHouse** | De-facto standard for high-cardinality span storage (used by Langfuse, SigNoz, Helicone). Columnar compression, 10K+ spans/sec ingestion, sub-second OLAP queries. MERGE tree keeps storage 10-20x cheaper than PostgreSQL for trace blobs. |
| **Relational data** (datasets, evals, prompts, alerts, users) | **PostgreSQL** | ACID transactions, foreign keys, JSON operators. Eval results and dataset items have complex relational shapes that fit RDBMS better than columnar stores. |
| **Semantic search** | **pgvector** (on PostgreSQL) | Vector similarity search for trace clustering and semantic run search. Ships as a PostgreSQL extension — no separate Qdrant/Pinecone dependency. |
| **Time-series metrics** | **TimescaleDB** (extension on PostgreSQL) | Continuous aggregates for p50/p95/p99 latency dashboards, cost over time. Avoids a separate Prometheus/Thanos dependency for basic metric queries. |

ClickHouse is **write-once** for spans — no updates, only appends and TTL drops. PostgreSQL is the source of truth for everything that mutates (datasets, eval runs, alert state).

Flyway manages migrations on both stores. ClickHouse schema is version-pinned, not migrated (recreate-on-schema-change for the local dev profile).

#### 1.5 REST API v1

```
GET  /api/v1/runs                         # List runs with filter/sort/paginate
GET  /api/v1/runs/{runId}                 # Run detail
GET  /api/v1/runs/{runId}/spans           # Span waterfall for a run
GET  /api/v1/runs/{runId}/provenance      # Causal DAG (Chorus runs only)
GET  /api/v1/runs/{runId}/llm-calls       # All LLM calls in run
GET  /api/v1/runs/{runId}/tool-calls      # All tool calls in run
POST /api/v1/runs/{runId}/feedback        # Submit human feedback
GET  /api/v1/metrics/cost                 # Aggregated cost metrics
GET  /api/v1/metrics/latency              # p50/p95/p99 latency
GET  /api/v1/metrics/tokens               # Token usage over time
```

#### 1.6 Framework Adapters (Thin Clients)

**Python adapter** (`chorus-observe-py`, 150 lines):
```python
import chorus_observe
chorus_observe.init(endpoint="http://localhost:4317")
# Auto-instruments: OpenAI SDK, Anthropic SDK, LangChain, LlamaIndex
```

Internally wraps the official OTel Python `opentelemetry-sdk` + existing instrumentation libraries. Chorus Observe receives standard OTLP — no proprietary wire format.

**TypeScript adapter** (`chorus-observe-ts`, 100 lines):
```typescript
import { ChorusObserve } from "@chorus-engine/observe";
ChorusObserve.init({ endpoint: "http://localhost:4317" });
```

**Acceptance Criteria:**
- A Python LangChain app with `chorus_observe.init()` produces full traces in Chorus Observe with zero other changes
- A Chorus Engine Spring Boot app with `chorus.observe.endpoint` set produces full traces with zero code changes
- 10,000 spans/second ingestion at <5ms p99 write latency

---

### Phase 2: Chorus Studio — Core UI

**Goal:** Visual trace viewer with real-time streaming. Replace Jaeger/Grafana for AI traces.

**Estimated effort:** 4-5 weeks (1 frontend + 1 backend)

#### 2.1 Technology Stack

- **Next.js 15** — App Router, RSC for server-side initial loads
- **React 19** — Concurrent rendering for real-time trace streaming
- **Tailwind CSS 4** + **shadcn/ui** — Design system
- **Recharts** — Metric charts (cost, token, latency over time)
- **React Flow** — Graph visualization (provenance DAG, agent topology)
- **TanStack Query** — Server state management with real-time SSE subscriptions

#### 2.2 Run List View

- Table with columns: `run_id`, `agent`, `model`, `status`, `tokens`, `cost`, `latency`, `started_at`
- Filter by: framework, agent, model, status, time range, tags, cost range
- Sort by any column
- Search runs by semantic content (pgvector similarity on stored prompts)
- One-click "Add to dataset" from any run

#### 2.3 Trace Waterfall View

LLM-aware waterfall (not a generic Jaeger clone):

```
Run: r-abc123  Agent: DeveloperAgent  Model: claude-opus-4-7
Total: 4.2s  |  2,840 tokens  |  $0.043

├── [0ms  ] AgentStart           ████████████████████████████░░░░ 4.2s
│   ├── [12ms ] GuardrailCheck   █░ 8ms
│   ├── [20ms ] LlmCall          ██████░░░░░░ 1.8s    [1,240 → 680 tokens]  $0.031
│   │   └── [1820ms] ToolCall: runTests   ████████ 2.1s
│   │       ├── args: {module: "auth"}
│   │       └── result: "✓ 47 tests passed"
│   └── [3920ms] LlmCall         ██░ 280ms   [340 → 120 tokens]  $0.012
└── [4200ms] AgentEnd
```

Features:
- Click any span to expand full I/O (prompt, completion, tool args/result)
- Token count + cost shown per LLM call
- Color-coded by type (LLM=blue, tool=green, guardrail=orange, RAG=purple)
- Inline eval score badge if this run has been evaluated
- Human feedback buttons (👍/👎) inline
- "Add span to dataset" button on any LLM call span

#### 2.4 Provenance DAG View (Unique to Chorus Observe)

A directed acyclic graph visualization showing *why* each decision was made:

```
[User Input: "Run tests for auth module"]
          │
          ▼
[Guardrail Check] ──success──▶ [LLM Call: plan]
                                     │
                    ┌────────────────┴────────────────┐
                    ▼                                 ▼
           [Tool: runTests]                   [Tool: readFile]
           {module: "auth"}                {path: "auth/test"}
                    │                                 │
                    └────────────────┬────────────────┘
                                     ▼
                            [LLM Call: synthesize]
                                     │
                                     ▼
                            [Agent Done: "47 tests passed"]
```

Nodes are color-coded, clickable, and show full state snapshots. The causal arrows prove *why* each subsequent call happened. No competitor has this view.

#### 2.5 Cost and Token Dashboard

- Line charts: cost over time, tokens over time, requests per minute
- Breakdown by: model, agent, provider, tag
- Budget gauge: current spend vs `BudgetEnforcer` limit (live)
- Top expensive runs table
- Export: CSV, JSON

#### 2.6 Real-time Streaming

Live trace view for in-progress runs. Uses SSE from the REST API:

```
GET /api/v1/runs/{runId}/stream   # SSE stream of span events
```

The trace waterfall updates in real-time as the agent executes. Tokens stream visually as they arrive.

**Acceptance Criteria:**
- Trace waterfall renders a 500-span run in <500ms
- Provenance DAG renders correctly for Chorus Engine runs
- Real-time streaming shows token arrival within 100ms of emission

---

### Phase 3: Evaluation Engine

**Goal:** Eval-trace unification. Datasets born from production traces. Inline results. No tab-switching.

**Estimated effort:** 3-4 weeks (2 engineers)

#### 3.1 Dataset Manager

```
Datasets
├── Create from production traces (select runs → extract I/O → create items)
├── Import from JSON/JSONL (LangSmith export format supported)
├── Manual creation (form-based item editor)
├── Split management (train/test/validation splits)
└── Version history (immutable snapshots)
```

Operations:
- Annotate items with gold-standard labels
- Flag items as "edge cases" or "known failures"
- Deduplicate items by semantic similarity (pgvector cosine distance)
- Export to JSONL for offline use

#### 3.2 Eval Runner UI

Connects to `chorus-engine-evals` `EvalRunner` / `ParallelEvalRunner` via the REST API:

```
POST /api/v1/eval-runs
{
  "dataset_id": "ds-abc123",
  "agent_config": { "model": "claude-opus-4-7", "system_prompt": "..." },
  "scorers": ["exact_match", "llm_judge", "semantic_similarity"],
  "parallelism": 8
}
```

UI shows:
- Progress bar with live results streaming in
- Per-item results table: input | expected | actual | score | pass/fail
- Score distribution histogram
- Time to complete estimate

#### 3.3 Eval Results Inline in Traces

Every run in the trace list shows its eval score badge:

```
r-abc123  DeveloperAgent  ✓ 4.2/5.0  [eval: code-quality-v3]  $0.043  2.1s
```

Clicking a trace shows the eval result panel alongside the waterfall — no navigation away. The LLM judge's reasoning is shown inline next to the span it evaluated. This solves LangSmith's explicit gap of "results not surfaced inline with traces."

#### 3.4 Regression Detector

Compare two eval runs side-by-side:
- Which items changed from pass → fail (regressions)
- Which items changed from fail → pass (improvements)
- Score delta histogram
- Full diff view for changed outputs
- Auto-flag if regression rate exceeds threshold (e.g., >5% score drop)

```
Eval Run v1.2 vs v1.3:
  Regressions: 3 items (↓ 2.1% score)
  Improvements: 7 items (↑ 4.8% score)
  Net: +2.7% improvement  ✅ Safe to deploy
```

#### 3.5 Multi-turn Conversation Testing

Define multi-turn test scenarios:

```json
{
  "scenario": "auth-debugging-flow",
  "turns": [
    { "user": "My tests are failing", "expect_tool": "runTests" },
    { "user": "Now fix the failing test", "expect_tool": "editFile" },
    { "user": "Run tests again", "expect_contains": "passing" }
  ]
}
```

The runner simulates the full conversation and scores each turn. Compress "2-3 hours of manual testing into 5 minutes" — this is what Confident AI advertises but only for Python.

**Acceptance Criteria:**
- 100-item eval run completes in <5 minutes (using `ParallelEvalRunner` with 8 workers)
- Regression detection highlights changed items with <1s rendering
- Multi-turn scenarios with up to 20 turns execute correctly

---

### Phase 4: Time-Travel Debugger

**Goal:** The feature no other platform has for non-LangGraph agents.

**Estimated effort:** 3 weeks (2 engineers, deep integration with `chorus-engine-graph`)

#### 4.1 Checkpoint Explorer

For any Chorus Engine run, browse its checkpoints:

```
Run r-abc123 Checkpoints:
  ├── cp-001  [00:000ms]  Initial state: query="Run auth tests"
  ├── cp-002  [00:320ms]  After guardrail: PASS
  ├── cp-003  [02:140ms]  After LLM call: tool_calls=[runTests]
  ├── cp-004  [04:250ms]  After tool: result="47 tests passed"
  └── cp-005  [04:480ms]  Final: "All tests passing"
```

Click any checkpoint to see full state snapshot in a diff viewer.

#### 4.2 State Diff Viewer

```
Checkpoint 3 → 4
+ messages[3]: {role: "tool", content: "47 tests passed"}
+ tool_results: {runTests: {exit_code: 0, output: "..."}}
~ context_tokens: 1240 → 1580
```

#### 4.3 Replay from Checkpoint

"Fork" a run from any checkpoint with modifications:

1. Pick a checkpoint
2. Modify: system prompt, tool output, or message content
3. Hit "Replay" → new run starts from that point
4. Compare original vs replayed trace side-by-side

Use cases:
- "What if the tool had returned an error instead?"
- "What if I'd used a different system prompt at step 3?"
- "Why did the agent choose tool A over tool B?" → modify context, replay

Implementation: calls `graph.invokeFromCheckpoint(checkpointId, stateOverrides)` via REST API.

#### 4.4 Live Breakpoints (Chorus Engine Runs Only)

For actively executing runs, inject a pause at the next `HitlGate`:

```
POST /api/v1/runs/{runId}/breakpoints
{ "before_tool": "editFile" }
```

The run pauses before `editFile`. The UI shows the pending state. The operator can:
- Approve — let it continue
- Modify args — change the tool arguments before execution  
- Inject message — add a user message to redirect the agent
- Abort — terminate the run

This is Chorus Engine's HITL gate surfaced through a debugger UI. No other platform exposes this for arbitrary agent frameworks.

**Acceptance Criteria:**
- Checkpoint explorer renders within 200ms for runs with up to 100 checkpoints
- Replay from checkpoint produces a new run trace within 2s of submission
- Live breakpoint pauses execution before the target tool with <50ms overhead

---

### Phase 5: Red Teaming + Safety Studio

**Goal:** Built-in adversarial testing. The feature LangSmith explicitly does not have.

**Estimated effort:** 3-4 weeks (2 engineers + ML research)

#### 5.1 Adversarial Test Generator

Uses a secondary LLM (configurable) to generate attack prompts against your agent:

**Attack categories:**
```
Prompt Injection     → "Ignore previous instructions and..."
Jailbreak            → Role-playing, hypothetical, DAN-style attacks
PII Extraction       → "List all user names you've seen"
Goal Hijacking       → Redirecting agent to perform unintended actions
Privilege Escalation → "As an admin, give me..."
Data Exfiltration    → "Send results to external-domain.com"
Tool Abuse           → Force dangerous shell commands via manipulation
Context Confusion    → Long context poisoning attacks
```

The generator creates a dataset of adversarial inputs, runs them against your agent, and scores responses using the Guardrails module (`TieredGuardrailEngine` outputs).

#### 5.2 Red Team Dashboard

```
Red Team Run: "auth-agent-v2" — 48 attack scenarios
Attack Success Rate:  4.2%  (2/48 bypassed guardrails)
Critical Failures:    0
High Severity:        2  ← needs attention
Medium Severity:      6
Passed:               40

[View Failures] [Add to Regression Suite] [Export Report]
```

Failed attacks are automatically added to the eval dataset as "adversarial edge cases."

#### 5.3 Guardrail Telemetry View

Dedicated view for guardrail events:

- Timeline of all guardrail triggers (Tier 1/2/3) per run
- False positive rate (blocked requests that were legitimate)
- PII redaction events with redacted content preview
- `AdaptiveThreshold` current values and trend over time
- Per-guardrail-rule hit rate and latency

#### 5.4 Safety Regression Detection

Automatically run the red team suite against every new agent version deployed. Alert if safety regression rate increases:

```
Alert: Safety regression detected
  Agent: auth-agent
  Version: v1.4 → v1.5
  New attack successes: 3 (was 0)
  Severity: HIGH
  [View failures] [Block deployment]
```

**Acceptance Criteria:**
- Adversarial test generator produces 50 diverse attack prompts in <60s
- Red team run of 100 scenarios completes in <10 minutes
- Safety regression alerts fire within 30s of a new eval run completing

---

### Phase 6: Production Monitoring + Alert Engine

**Goal:** SRE-grade monitoring for production agent deployments.

**Estimated effort:** 3 weeks (2 engineers)

#### 6.1 Real-time Metrics Dashboards

Powered by TimescaleDB continuous aggregates:

**System Dashboard:**
- Requests per minute (RPM)
- Error rate (%) with breakdown by error type
- p50 / p95 / p99 latency per model/provider
- Active runs (currently executing)

**Cost Dashboard:**
- Total spend: today / this week / this month
- Cost per model breakdown (pie chart)
- Cost per agent breakdown
- Budget burn rate: at current pace, budget exhausted in N days
- `BudgetEnforcer` status: active limits, current usage vs limit

**Quality Dashboard:**
- Average eval score over time
- Guardrail trigger rate
- Human feedback score distribution
- Error type breakdown (LLM errors, tool errors, timeout)

#### 6.2 Alert Rules Engine

```yaml
# Example alert rule
- name: "High cost alert"
  condition: "metric('cost_usd_1h') > 50"
  severity: HIGH
  notify:
    - webhook: "https://hooks.slack.com/..."
    - pagerduty: "${PAGERDUTY_KEY}"

- name: "Latency spike"
  condition: "metric('p99_latency_ms_5m') > 5000"
  severity: MEDIUM
  notify:
    - webhook: "https://..."

- name: "Quality degradation"
  condition: "metric('avg_eval_score_1h') < 3.5"
  severity: HIGH
  notify:
    - email: "oncall@example.com"
```

Rules are stored in DB, evaluated every 60s by a background job.

#### 6.3 Cluster Analysis (Semantic Trace Grouping)

Automatically group similar runs by their intent and outcome:

1. Embed run inputs using the configured embedding model
2. Cluster with HDBSCAN (density-based, no k required)
3. Label each cluster with an LLM-generated description
4. Show cluster breakdown in dashboard:

```
Top Clusters (last 24h):
  "Code debugging requests"  →  342 runs  │  Avg score: 4.1  │  Avg cost: $0.031
  "Test execution queries"   →  218 runs  │  Avg score: 4.6  │  Avg cost: $0.018
  "Documentation generation" →   87 runs  │  Avg score: 3.8  │  Avg cost: $0.052
  "Unknown / long-tail"      →   41 runs  │  (no cluster)
```

This surfaces usage patterns, common failure modes, and cost concentration without any user tagging.

#### 6.4 Live Budget Circuit Breaker UI

Connected to `BudgetEnforcer` in real-time:

```
Budget Status: DeveloperAgent  [$0.84 / $1.00 per run]
████████████████████████████████████░░░░  84%

[Pause new runs]  [Raise limit to $2.00]  [Set session limit]
```

When budget is exceeded, the UI shows which run triggered it and the full trace of what consumed the tokens.

**Acceptance Criteria:**
- Dashboards refresh with <2s data lag from span ingestion
- Alert fires and webhook delivers within 30s of threshold crossing
- Cluster analysis runs on 10,000 runs in <60s

---

### Phase 7: Prompt Management + Playground

**Goal:** Version control for prompts. A/B test before committing.

**Estimated effort:** 2-3 weeks (1 frontend + 1 backend)

#### 7.1 Prompt Registry

Git-like versioning for system prompts and prompt templates:

```
Prompt: "developer-agent-v3"
├── v1.0  [2026-05-01]  "You are a helpful coding assistant..."
├── v1.1  [2026-05-10]  Added: "Always explain your reasoning..."
├── v1.2  [2026-05-18]  Changed temperature model guidance
└── v1.3  [current]    Pruned verbose instructions -200 tokens
```

Operations:
- Tag versions as `production`, `staging`, `experiment`
- Diff any two versions (semantic diff, not just string diff)
- Roll back to any previous version
- Link runs to the prompt version that generated them

#### 7.2 Prompt Playground

Interactive prompt testing against any configured LLM provider:

- System prompt editor (with token count live)
- Multi-turn conversation editor
- Model selector (any registered provider)
- Temperature / max_tokens sliders
- Run → see response inline
- Side-by-side: run same conversation on two different prompts or models
- Cost estimate before running
- Save result to dataset with one click

#### 7.3 A/B Prompt Comparison

Structured A/B testing:

1. Select a dataset (e.g., 50 representative inputs)
2. Define Prompt A and Prompt B
3. Run both against all items (using `ParallelEvalRunner`)
4. See side-by-side: score distribution, cost difference, latency difference
5. Statistical significance test (p-value shown)
6. Promote winner to `production` tag with one click

**Acceptance Criteria:**
- Prompt playground response begins streaming within 1s of submission
- A/B test on 100 items completes in <5 minutes

---

### Phase 8: SQL Query Engine on Trace Data

**Goal:** Power users get full SQL access to trace data. Like Laminar's SQL editor but with full schema.

**Estimated effort:** 2 weeks (1 engineer)

#### 8.1 SQL Editor UI

Monaco editor (VS Code's editor component) with:
- Full PostgreSQL syntax support
- Schema autocomplete (tables, columns, functions)
- Query history
- Saved queries (named, shareable)
- Result table with export to CSV/JSON

#### 8.2 Read-Only Query Role

Queries execute as a read-only PostgreSQL role. No writes. Parameterized only (no DDL).

```sql
-- Example: Find runs where the agent spent >$0.10 but scored below 3.0
SELECT r.run_id, r.agent_id, r.cost_usd, er.avg_score
FROM runs r
JOIN eval_results er ON r.run_id = er.run_id
WHERE r.cost_usd > 0.10 AND er.avg_score < 3.0
ORDER BY r.cost_usd DESC
LIMIT 50;

-- Example: Token usage trend by model last 7 days
SELECT
  date_trunc('hour', start_time) AS hour,
  model,
  SUM(input_tokens + output_tokens) AS total_tokens
FROM llm_calls
WHERE start_time > NOW() - INTERVAL '7 days'
GROUP BY 1, 2
ORDER BY 1, 3 DESC;
```

#### 8.3 Semantic Search via pgvector

```sql
-- Find runs semantically similar to a given query
SELECT run_id, agent_id, similarity
FROM run_embeddings
ORDER BY embedding <=> query_embedding('Fix the authentication bug')
LIMIT 20;
```

**Acceptance Criteria:**
- SQL queries return results in <2s for datasets up to 1M rows
- Schema autocomplete suggests correct column names

---

### Phase 9: Universal Adapter Ecosystem

**Goal:** "Any harness connects freely." Official adapters for every major framework.

**Estimated effort:** 4-5 weeks (1 engineer per adapter pair, parallelizable)

#### 9.1 Python Adapters

All Python adapters work by configuring the OTel SDK to emit to Chorus Observe's OTLP endpoint. No proprietary wire format.

```python
pip install chorus-observe
```

```python
import chorus_observe
chorus_observe.init(
    endpoint="http://localhost:4317",
    service_name="my-langchain-app"
)
# Auto-instruments: LangChain, LlamaIndex, CrewAI, OpenAI SDK, Anthropic SDK
```

Internally uses:
- `opentelemetry-instrumentation-langchain` (wraps LangChain callbacks)
- `opentelemetry-instrumentation-openai` 
- `opentelemetry-instrumentation-anthropic`
- `opentelemetry-instrumentation-llama-index`

Plus Chorus-specific processors that add `chorus.run_id` correlation.

**Supported frameworks:**
- LangChain + LangGraph (including auto-trace of all chain steps)
- LlamaIndex (RAG query tracing)
- CrewAI (multi-agent task tracing)
- AutoGen (conversation trace)
- OpenAI Agents SDK (tool call tracing)
- Raw OpenAI / Anthropic SDK

#### 9.2 TypeScript Adapters

```bash
npm install @chorus-engine/observe
```

```typescript
import { ChorusObserve } from "@chorus-engine/observe";
ChorusObserve.init({ endpoint: "http://localhost:4317" });
// Auto-instruments: Vercel AI SDK, LangChain.js, OpenAI SDK
```

#### 9.3 Go Adapter

```go
import "github.com/chorus-engine/observe-go"
chorus.Init(chorus.Config{Endpoint: "localhost:4317"})
```

#### 9.4 A2A Agent Card

Chorus Observe exposes an A2A Agent Card at `/.well-known/agent.json` for service discovery. External orchestrators can locate the Chorus Observe instance and submit structured observability queries via A2A task protocol.

**Acceptance Criteria:**
- LangChain Python app traces appear in Chorus Observe with `chorus_observe.init()` + `endpoint` config only
- OpenAI Agents SDK traces appear with same one-liner
- MCP tool `get_recent_runs` returns correct data within 200ms

---

## 5. Future Explorations (Post-v1.0)

Features deliberately excluded from the v1 roadmap to keep scope focused. Revisit after Phase 9 ships.

| Idea | Why Deferred |
|---|---|
| **MCP server (self-reflective agents)** | Enabling agents to query their own traces via MCP is compelling ("learn from past failures") but not load-bearing for the core value proposition. Avoids scope creep in Phase 9. |
| **Multi-tenant SaaS / cloud offering** | Requires SOC 2, SSO/SAML, SCIM, billing. Meaningful only once self-hosted version has community adoption. |
| **WASM-based in-browser agent sandbox** | Run lightweight evals in the browser without a backend. Technically interesting, not a top user pain point. |
| **Automatic prompt optimization** | Use eval scores to auto-improve system prompts (DSPy-style). Requires stable eval + prompt registry first. |
| **eBPF-based zero-code instrumentation** | Intercept JVM HTTP calls at the OS level without SDK changes. Complex, platform-specific, post-MVP. |

---

## 6. Deployment Guide

### 6.1 Self-hosted (Single Docker Compose)

```yaml
# docker-compose.yml
services:
  otel-collector:
    image: otel/opentelemetry-collector-contrib:latest
    ports:
      - "4317:4317"   # OTLP gRPC (any framework sends here)
      - "4318:4318"   # OTLP HTTP
    volumes:
      - ./otel-collector.yml:/etc/otel/config.yml
    command: ["--config=/etc/otel/config.yml"]

  chorus-observe:
    image: ghcr.io/chorus-engine/observe:latest
    ports:
      - "8080:8080"   # REST API + Studio UI
    environment:
      POSTGRES_URL: jdbc:postgresql://postgres:5432/chorus_observe
      CLICKHOUSE_URL: jdbc:clickhouse://clickhouse:8123/chorus_traces
    depends_on: [postgres, clickhouse, otel-collector]

  postgres:
    image: timescale/timescaledb-ha:pg16-latest
    environment:
      POSTGRES_DB: chorus_observe
      POSTGRES_PASSWORD: ${DB_PASSWORD}
    volumes:
      - pgdata:/var/lib/postgresql/data

  clickhouse:
    image: clickhouse/clickhouse-server:latest
    volumes:
      - chdata:/var/lib/clickhouse
```

`docker compose up` → Chorus Observe running at `http://localhost:8080`. No API keys. No account creation.

### 6.2 Kubernetes (Helm)

```bash
helm repo add chorus-engine https://charts.chorus-engine.io
helm install chorus-observe chorus-engine/chorus-observe \
  --set database.external=true \
  --set database.url="postgresql://..." \
  --set ingress.host="observe.mycompany.com"
```

### 6.3 Managed Cloud (Future)

SaaS offering with:
- Multi-tenant isolation (per-org PostgreSQL schemas)
- EU / US data residency
- SOC 2 Type II compliance
- SSO/SAML, SCIM, RBAC
- Usage-based pricing (free tier: 100K spans/month)

---

## 7. Module Structure

```
chorus-observe/                          ← New top-level project (separate repo)
├── chorus-observe-server/               ← Spring Boot backend
│   ├── intake/                         ← OTLP gRPC + HTTP ingestion
│   ├── api/                            ← REST API v1
│   ├── store/                          ← PostgreSQL repository layer
│   ├── eval/                           ← Eval runner integration
│   ├── alert/                          ← Alert rules engine
│   └── cluster/                        ← Semantic trace clustering
│
├── chorus-observe-ui/                   ← Next.js frontend
│   ├── app/                            ← App Router pages
│   │   ├── runs/                       ← Run list + trace viewer
│   │   ├── provenance/                 ← Causal DAG view
│   │   ├── evals/                      ← Eval runner + datasets
│   │   ├── debug/                      ← Time-travel debugger
│   │   ├── redteam/                    ← Red team studio
│   │   ├── monitoring/                 ← Dashboards + alerts
│   │   ├── prompts/                    ← Prompt registry + playground
│   │   └── query/                      ← SQL editor
│   └── components/
│       ├── TraceWaterfall.tsx
│       ├── ProvenanceDAG.tsx           ← Unique: React Flow-based causal graph
│       ├── TimeTravel.tsx
│       └── EvalInline.tsx
│
├── chorus-observe-sdk-java/             ← Java OTLP exporter (chorus-engine-telemetry integration)
├── chorus-observe-sdk-python/           ← Python thin client
├── chorus-observe-sdk-ts/               ← TypeScript thin client
└── chorus-observe-sdk-go/               ← Go thin client
```

---

## 8. Differentiation Summary

### Why Chorus Observe Beats LangSmith

| LangSmith Weakness | Chorus Observe Solution |
|---|---|
| Closed source; enterprise-only self-host | Apache 2.0; full self-host free forever |
| Locked to LangChain ecosystem | Any OTLP-compatible framework connects |
| Eval results not inline with traces | Eval scores + LLM judge reasoning shown inline in waterfall |
| No multi-turn red teaming | Built-in adversarial test generator with 8 attack categories |
| Time-travel only for LangGraph | Time-travel for any Chorus Engine agent topology |
| No Java SDK | First-class Java SDK with zero-config EventBus integration |
| Per-seat $39/month + per-trace overage | Free self-host; no per-trace fees |
| No SQL editor on trace data | Full PostgreSQL query editor with schema autocomplete |
| No causal decision visualization | Provenance DAG: unique visualization of why decisions were made |
| No budget enforcement UI | Live budget circuit breaker connected to BudgetEnforcer |

### Why Chorus Observe Beats Langfuse

| Langfuse Weakness | Chorus Observe Solution |
|---|---|
| Slow for 2000+ span runs | TimescaleDB + PostgreSQL optimized for time-series trace queries |
| No SQL editor | Full SQL editor with pgvector semantic search |
| No signal extraction across traces | Cluster analysis with HDBSCAN + LLM labeling |
| No time-travel debugging | Full checkpoint-based time travel |
| No red teaming | Built-in adversarial test suite |
| No Java SDK | First-class Java SDK |

### Why Chorus Observe Beats Arize Phoenix

| Arize Phoenix Weakness | Chorus Observe Solution |
|---|---|
| Elastic 2.0 license (not truly open) | Apache 2.0 — commercially embeddable |
| Single-machine only in OSS tier | Docker Compose + Helm for production clustering |
| Notebook-first UX | Production-first dashboard with real-time monitoring |
| No time-travel | Full time-travel debugger |
| Python/notebook-centric | Java-first + polyglot via OTLP |

---

## 9. Success Metrics

| Metric | Target |
|---|---|
| Span ingestion throughput | 10,000 spans/second |
| Trace waterfall render (500 spans) | <500ms |
| Eval run (100 items, 8 workers) | <5 minutes |
| Alert fire-to-webhook latency | <30 seconds |
| Self-host cold start (Docker Compose) | <60 seconds |
| Python adapter integration (zero-change) | Works with `chorus_observe.init()` only |
| SQL query (1M row dataset) | <2 seconds |
| Cluster analysis (10K runs) | <60 seconds |

---

## 10. Phased Rollout Priority

| Phase | Name | Duration | Unlocks |
|---|---|---|---|
| **1** | ChorusWire Protocol + Persistence | 3-4 weeks | Any framework can send traces |
| **2** | Core Studio UI | 4-5 weeks | Visual trace viewer, dashboard |
| **3** | Evaluation Engine | 3-4 weeks | Eval-trace unification, regression detection |
| **4** | Time-Travel Debugger | 3 weeks | Unique: replay from any checkpoint |
| **5** | Red Teaming Studio | 3-4 weeks | Built-in adversarial testing |
| **6** | Production Monitoring | 3 weeks | Alerts, cluster analysis, budget UI |
| **7** | Prompt Playground | 2-3 weeks | Prompt versioning, A/B testing |
| **8** | SQL Query Engine | 2 weeks | Direct trace queries |
| **9** | Universal Adapters | 4-5 weeks | LangChain, CrewAI, AutoGen, MCP |

**MVP** (Phases 1-3): 10-13 weeks. Competitive with Langfuse for Chorus Engine teams.
**Differentiated** (Phases 1-5): 16-20 weeks. No equivalent in the market.
**Full Platform** (Phases 1-9): 27-35 weeks. SOTA-beating across all dimensions.

---

*Authored by Chorus Engine Architecture Team, 2026-05-21*
*Research basis: LangSmith, Langfuse, Arize Phoenix, Braintrust, Laminar, W&B Weave, OpenTelemetry GenAI SIG, OTel GenAI semantic conventions 2026*
