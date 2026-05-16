# Enterprise Harness

The harness module (`@chorus/engine/harness`) provides production-grade task routing and multi-worker execution. It replaces the naive `Promise.all` parallel prompting with **semantic routing**, **phased execution**, and **shared context passing**.

## Semantic Router

Replaces regex keyword matching with embedding-based intent classification:

```typescript
import { routeTaskSemantic, SemanticTaskRouter } from "@chorus/engine/harness";

// Convenience function
const route = await routeTaskSemantic({ text: "Debug auth module" });

// Full control
const router = new SemanticTaskRouter({ confidenceThreshold: 0.75 });
const scores = await router.score({ text: "Debug auth module" });
// [{ label: "debug", confidence: 0.92 }, { label: "research", confidence: 0.34 }, ...]
```

### Route Kinds

| Kind | Confidence | Description |
|------|-----------|-------------|
| `answer_only` | High | Simple question, no file access needed |
| `single_file_edit` | High | One-file change (refactor, fix) |
| `multi_file_edit` | High | Cross-file change (feature, refactor) |
| `research` | High | Requires external knowledge |
| `debug` | High | Diagnostic / error fixing |
| `project_phase` | High | Entire-project task (audit, migrate) |
| `OOD` | Low | Out-of-distribution, needs escalation |

### Hybrid Classification

The router uses a **two-stage cascade**:

1. **Embedding similarity** (fast path): Cosine similarity against 6 route prototypes
2. **Regex fallback** (slow path): If confidence < threshold, use keyword heuristics
3. **Confidence calibration**: Research routes use `τ_FAQ ≈ 0.85`; OOD uses `τ_OOD ≈ 0.5`

This achieves ~94% accuracy at ~60% cost reduction vs pure LLM classification (per CoRouter research).

## Worker Execution

### Modes

#### Parallel Mode (default)

All workers run concurrently, up to `concurrency` limit:

```typescript
const results = await executeWorkers({
  assignments: [
    { workerId: "w1", role: "researcher", ... },
    { workerId: "w2", role: "planner", ... },
    { workerId: "w3", role: "coder", ... },
  ],
  executionMode: "parallel",
  concurrency: 3,
});
```

Use for: Independent tasks (research multiple topics, review multiple files).

#### Pipeline Mode

Workers run sequentially, with accumulated context:

```typescript
const results = await executeWorkers({
  assignments: [
    { workerId: "w1", role: "researcher", ... },
    { workerId: "w2", role: "planner", ... },
    { workerId: "w3", role: "coder", ... },
  ],
  executionMode: "pipeline",
});
```

Execution order: `discovery` → `planning` → `execution` → `verification`.

Each phase's output is written to shared memory and included in downstream prompts:

```
Researcher: "Found 3 relevant docs"
  ↓ [shared context]
Planner: "Based on findings: design API with 3 endpoints"
  ↓ [shared context]
Coder: "Implementing: GET /users, POST /users, DELETE /users"
```

Use for: Dependent tasks (research → plan → code → verify).

### Shared Context

Workers can read/write a shared key-value store:

```typescript
import { createSharedContext } from "@chorus/engine/harness";

const shared = createSharedContext();
shared.set("design.decisions", ["Use REST", "JWT auth"]);

const results = await executeWorkers({
  assignments: [...],
  sharedContext: shared,
});

shared.get("worker.researcher"); // WorkerExecutionResult
```

The default shared context is created automatically in pipeline mode. Results are keyed by `worker.{role}`.

### Concurrency Control

- **`concurrency: number`** — Max parallel workers (default: 3)
- **`abortSignal: AbortSignal`** — Cancel all pending workers
- **Worker timeout** — Each worker has a default 60s timeout

### Failure Handling

| Scenario | Behavior |
|----------|----------|
| Single worker fails | Other workers continue; failure logged in result |
| All workers fail | Returns all failures; upstream decides retry |
| AbortSignal triggered | Pending workers cancelled; resolved results returned |
| Provider error | Retried per `withRetry` policy (3× backoff) |

## Worker Roles

| Role | Phase | Purpose |
|------|-------|---------|
| `researcher` | discovery | Gather context, search docs, find examples |
| `planner` | planning | Design approach, break down tasks |
| `coder` | execution | Generate code, apply edits |
| `reviewer` | verification | Review output, flag issues |
| `tester` | verification | Generate tests, verify correctness |

## Events

The `onEvent` callback receives:

```typescript
type WorkerEvent =
  | { type: "worker-start"; workerId: string; role: string }
  | { type: "worker-update"; workerId: string; status: string }
  | { type: "worker-complete"; workerId: string; summary: string }
  | { type: "worker-fail"; workerId: string; error: string }
  | { type: "task-start"; taskId: string }
  | { type: "task-complete"; taskId: string }
```

## Task Routing Integration

```typescript
import { prepareTaskExecution } from "@chorus/engine/harness";

const prepared = prepareTaskExecution({
  text: "Refactor auth module",
  expandedText: "...",
  basePrompt: "...",
  messages: [...],
});

prepared.route.kind;       // "single_file_edit"
prepared.route.confidence; // 0.91
prepared.route.lane;       // "edit"
```

`prepareTaskExecution` returns both the route and the formatted system prompt for the assigned workers.
