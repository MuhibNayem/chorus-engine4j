# Phase 4 Context: Low-Risk Additive Features

**Phase:** 4 of 6
**Milestone:** v1.0 Enterprise Feature Parity
**Status:** Planning complete — ready to execute

## Requirements Addressed

| Requirement | Description |
|-------------|-------------|
| ALERT-01 | Microsoft Teams notification channel |
| ALERT-02 | Adaptive Card alert formatting for Teams |
| ALERT-03 | Alert delivery retry with exponential backoff |
| EVAL-01 | Hallucination evaluator engine |
| EVAL-02 | Automated evaluator triggers on run completion |

## Decisions Locked

| Area | Decision |
|------|----------|
| Teams Integration | Add `TEAMS` to `ChannelType` enum; fixed Adaptive Card template (MessageCard v1.4); webhook URL from channel config |
| Alert Retry | Add `retry_count`, `next_retry_at`, `last_error` to `alert_events`; separate `@Scheduled` poller; 3 retries with exponential backoff (30s → 120s → 480s) |
| Hallucination Scoring | Hybrid approach: n-gram overlap as primary scorer, optional LLM-judge via configurable HTTP endpoint |
| Evaluator Kind | `kind="hallucination"` in evaluator config; score 0.0–1.0; `passed` threshold configurable (default 0.7) |
| Auto-trigger | Spring `ApplicationEvent` (`RunCompletedEvent`) + `@EventListener`; fired when run reaches terminal state (SUCCESS or ERROR) in `OtlpIngestionService.flushRun()` |

## Existing Infrastructure to Leverage

- `NotificationDispatcher` interface with 4 implementations (Slack, PagerDuty, Email, Webhook)
- `NotificationService` routes alerts to channels via `Map<ChannelType, NotificationDispatcher>`
- `AlertEvent` and `AlertRule` models with JDBC repositories
- `EvaluatorService` with stub `evaluateRun()` — no execution engine exists
- `RunEvaluation` model with `score`, `passed`, `details` JSONB
- `LlmCall` model with `prompt`, `completion`, `messages` fields
- `RunAccumulator` in `OtlpIngestionService` tracks run state across span flushes

## Constraints

- Zero external HTTP client libraries — use JDK `HttpClient`
- Zero Mockito — hand-written fakes only
- No schema changes needed for `ChannelType` (VARCHAR in DB, no CHECK constraint)
- `Run` model has no `output` field — hallucination scorer reads from linked `LlmCall` records
- `EvaluatorService` currently has no `LlmCallRepository` dependency — needs injection
