# Chorus Engine Architecture

## Overview

`@chorus/engine` is a headless agent execution library that separates the orchestration engine from presentation. The CLI (`Chorus-cli`) is a consumer of this engine. This document describes the internal architecture, data flow, and design decisions.

## Design Principles

1. **Headless**: No UI dependencies. All I/O is via async generators and event callbacks.
2. **Type-safe**: `strict: true`, zero `any` escapes. Every boundary is typed.
3. **Resilient**: Retry with exponential backoff, stream timeouts, checkpoint recovery.
4. **Observable**: OpenTelemetry-compatible telemetry throughout.
5. **Extensible**: Plugin architecture for LLM providers, tools, and middleware.

## System Layers

```
┌──────────────────────────────────────────────────────────────────────┐
│                        Consumer (CLI / API / Bot)                     │
├──────────────────────────────────────────────────────────────────────┤
│  SwarmRouter │ Harness │  Agent Loop  │  EvalRunner  │  SkillManager │
├──────────────────────────────────────────────────────────────────────┤
│  HITL Gate   │ Checkpointer │ Telemetry │ Synthesizer │ MCP Client   │
├──────────────────────────────────────────────────────────────────────┤
│  LLM Provider (OpenAI / Anthropic / Local / Mock)                    │
├──────────────────────────────────────────────────────────────────────┤
│  Tool Registry │ File System │ Vector Store │ External APIs          │
└──────────────────────────────────────────────────────────────────────┘
```

## Agent Loop

The core ReAct loop (`src/agent/loop.ts`):

```
User Input → Middleware Chain → LLM Stream → Token/Thinking Events
                                    ↓
                              Tool Calls → Tool Execution → Tool Results
                                    ↓
                              Checkpoint → Resume on Restart
                                    ↓
                              Output Schema Validation → Yield Result
```

### Stream Resilience

The loop wraps `provider.streamWithTools()` in `consumeStream()`, which provides:

- **3-attempt retry** for retryable errors (ECONNRESET, 5xx, etc.) before any tokens are yielded
- **Per-chunk timeout** via `withStreamTimeout()` — aborts hung providers
- **Fatal boundary** — retryable errors after token emission are fatal (consistency > availability)

### Middleware Chain

Pre/post hooks for:
- Prompt augmentation
- Tool injection
- Response filtering
- Logging / audit

## Swarm Router

Multi-agent delegation (`src/swarm/`):

```
User Request → SwarmRouter → Select Agent → Delegate → Collect Result
                                ↓
                          Scope-based ownership (file, module, domain)
```

Agents are specialized by `systemPrompt` and `tools`. The router uses:
1. **Explicit routing**: `handoff_to_agent` tool call
2. **Implicit routing**: Regex heuristics on user intent

## Harness

Enterprise task routing and worker execution (`src/harness/`):

```
User Request → Semantic Router → TaskRoute → Worker Assignments
                                              ↓
                                    executeWorkers()
                                              ↓
                              Parallel Mode: all workers concurrently
                              Pipeline Mode: discovery → planning → execution
```

See [HARNESS.md](HARNESS.md) for the full worker execution model.

## HITL Gate

Human-in-the-loop with production safety:

- **Session-scoped**: Gates are per-session, not global
- **Configurable timeout**: Default 5 minutes, prevents immortal resolvers
- **Disposal**: `dispose()` rejects all pending gates with `HitlGateDisposedError`
- **Policy levels**: `ask_before_edit`, `ask_before_command`, `auto_edit`, `none`

## Checkpointer

State persistence for crash recovery:

- **Write-on-turn**: Every completed turn is checkpointed
- **ID-based lookup**: Resume from `turnId`
- **Memory fallback**: In-memory store for testing
- **Disk adapter**: JSONL file for production

## Skill Synthesis

Auto-extracts reusable skill patterns:

- **Trajectory extraction**: `Map<string, currentTool>` by `tool_call_id` for correct parallel tool call pairing
- **LCS alignment**: Finds common subsequence across successful executions
- **Pattern generalization**: Replaces concrete values with typed placeholders
- **FIFO cap**: `maxTrajectories` (default 1,000) prevents unbounded growth

## Telemetry

OpenTelemetry-compatible tracing:

- **Span per turn**: `agent.turn`, `agent.tool_call`, `harness.worker`
- **Metrics**: Token counts, latency, error rates
- **Export**: OTLP / Console / In-memory (for testing)

## Data Flow: Full Execution

```
1. User sends message
2. SemanticRouter.classify() → TaskRoute
3. Harness.assignWorkers(route) → WorkerAssignment[]
4. executeWorkers() → WorkerExecutionResult[]
5. TrajectorySynthesizer.observe(results) → SkillCandidate[]
6. runAgentLoop() with synthesized skills → Stream events
7. HITL Gate intercepts if policy requires approval
8. Checkpointer.save(turn) → Disk
9. Telemetry.export(span) → Collector
```

## Error Handling Strategy

| Layer | Strategy |
|-------|----------|
| Network (LLM) | Retry 3× with backoff; fatal after token emission |
| Stream | Per-chunk timeout; abort signal propagation |
| Tool | Tool-level error → yield as tool result, not crash |
| HITL | Timeout → reject with `HitlGateTimeoutError` |
| Checkpointer | Best-effort; never block agent loop |
| Telemetry | Best-effort; never block agent loop |

## Thread Safety

- **Agent Loop**: Single-threaded per instance. Do not share `AgentContext` across concurrent loops.
- **Swarm**: Router is stateless; agents are per-instance.
- **Harness**: Workers run in parallel but each has isolated context. Shared state via `SharedContext` interface.
- **Checkpointer**: Async-safe via promise queue.

## Extension Points

| Extension | Interface |
|-----------|-----------|
| LLM Provider | `LLMProvider` |
| Tool | `Tool` (Zod schema + execute fn) |
| Middleware | `Middleware` (pre/post hooks) |
| Checkpointer | `Checkpointer` |
| Telemetry Exporter | `TelemetryExporter` |
| Shared Context | `SharedContext` (for harness) |
