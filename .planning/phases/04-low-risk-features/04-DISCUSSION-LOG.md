# Phase 4 Discussion Log

**Date:** 2026-05-23
**Participants:** Human PM, AI

## Topics Discussed

### 1. Microsoft Teams Notification Channel (ALERT-01, ALERT-02)

**Decision:** Add `TEAMS` to `ChannelType` enum; create `TeamsDispatcher` with a fixed Adaptive Card template (MessageCard v1.4).

- Webhook URL read from channel config (`webhookUrl` key).
- No per-rule card customization — one fixed template with severity-based color theming.
- Template includes: severity emoji, rule name, triggered value, threshold, timestamp, deep link placeholder.
- Uses JDK `HttpClient` (consistent with existing dispatchers).

### 2. Alert Delivery Retry (ALERT-03)

**Decision:** Add retry columns to `alert_events` table, create `AlertRetryScheduler` with `@Scheduled` polling.

- Columns: `retry_count INT DEFAULT 0`, `next_retry_at TIMESTAMPTZ`, `last_error TEXT`.
- `NotificationService.dispatchAlert()` updates these columns on failure instead of just logging.
- Scheduler polls `next_retry_at <= NOW() AND retry_count < 3` every 60s.
- Backoff: 30s → 120s → 480s (4x multiplier).
- After 3 failures, event stays in table with `retry_count = 3` for audit; no further retries.

### 3. Hallucination Evaluator Engine (EVAL-01)

**Decision:** Hybrid n-gram + optional LLM-judge scorer.

- Primary: `NgramHallucinationScorer` — computes n-gram overlap between completion and prompt/context messages. Score = 1.0 - (overlap ratio). High score = high hallucination risk.
- Optional: `LlmJudgeHallucinationScorer` — HTTP POST to configured endpoint with prompt+completion, parses JSON response for score.
- Evaluator config controls which scorer(s) to use and threshold (default 0.7).
- `EvaluatorService.evaluateRun()` routes by `kind="hallucination"`, reads `LlmCall` records for the run, executes scorer, persists `RunEvaluation`.

### 4. Automated Evaluator Triggers (EVAL-02)

**Decision:** Spring `ApplicationEvent` + `@EventListener`.

- `RunCompletedEvent` carries `runId`, `tenantId`, `status`.
- Published from `OtlpIngestionService.flushRun()` when run reaches terminal state (SUCCESS or ERROR) for the first time.
- `RunCompletedEventListener` receives event, queries evaluators with `kind="hallucination"`, calls `EvaluatorService.evaluateRun()` for each.
- No async executor — event handling is synchronous (simplest, no thread pool management).

## Deferred Items

| Item | Reason |
|------|--------|
| Teams card customization per rule | Too complex for additive phase; fixed template covers 90% of use cases |
| Retry via message queue (RabbitMQ/SQS) | Overkill for current scale; DB polling is sufficient |
| Multiple evaluator kinds auto-triggered | Only hallucination in this phase; pattern extensible |
| LLM-judge caching / batching | Can be added later without breaking API |
