# Phase 4 Summary: Low-Risk Additive Features

**Status:** Planning complete — ready to execute
**Requirements:** ALERT-01, ALERT-02, ALERT-03, EVAL-01, EVAL-02

---

## Decisions Locked

| Area | Decision |
|------|----------|
| Teams | `TEAMS` enum value, fixed Adaptive Card v1.4 template, webhook URL from config |
| Retry | `retry_count`, `next_retry_at`, `last_error` on `alert_events`, `@Scheduled` poller, 3 retries with exponential backoff |
| Hallucination | N-gram overlap primary + optional LLM-judge HTTP endpoint, `kind="hallucination"` |
| Auto-trigger | `RunCompletedEvent` + `@EventListener`, fired from `OtlpIngestionService.flushRun()` on terminal state |

## Plans

| Plan | Wave | Goal | Requirements |
|------|------|------|-------------|
| [PLAN-04-01](PLAN-04-01.md) | 1 | Teams Notification Channel — add TEAMS enum, TeamsDispatcher with Adaptive Card, register bean, tests | ALERT-01, ALERT-02 |
| [PLAN-04-02](PLAN-04-02.md) | 2 | Alert Retry with Exponential Backoff — Flyway V9, update AlertEvent model/repo, retry scheduler, tests | ALERT-03 |
| [PLAN-04-03](PLAN-04-03.md) | 3 | Hallucination Evaluator Engine — NgramHallucinationScorer, optional LlmJudgeHallucinationScorer, wire into EvaluatorService.evaluateRun(), tests | EVAL-01 |
| [PLAN-04-04](PLAN-04-04.md) | 4 | Automated Evaluator Triggers — RunCompletedEvent, publish from OtlpIngestionService, @EventListener, integration tests | EVAL-02 |

## Dependencies

```
04-01 (Teams) ─┐
04-02 (Retry) ─┤
04-03 (Hallucination) ─┬─→ 04-04 (Auto-trigger)
```

## New Files (estimated)

- `notification/TeamsDispatcher.java`
- `notification/AlertRetryScheduler.java`
- `eval/HallucinationScorer.java`
- `eval/NgramHallucinationScorer.java`
- `eval/LlmJudgeHallucinationScorer.java`
- `event/RunCompletedEvent.java`
- `event/RunCompletedEventListener.java`
- `db/migration/V9__alert_retry.sql`
- `notification/TeamsDispatcherTest.java`
- `eval/NgramHallucinationScorerTest.java`
- `event/RunCompletedEventListenerTest.java`

## Modified Files (estimated)

- `model/NotificationChannel.java` (+ TEAMS)
- `model/AlertEvent.java` (+ retryCount, nextRetryAt, lastError)
- `persistence/AlertEventRepository.java` (+ retry columns)
- `notification/NotificationService.java` (+ failure tracking)
- `service/EvaluatorService.java` (+ actual execution, LlmCallRepository)
- `service/OtlpIngestionService.java` (+ RunCompletedEvent publish)
- `config/ChorusObserveAutoConfiguration.java` (+ TeamsDispatcher, AlertRetryScheduler, scorer beans)
- `persistence/EvaluatorRepository.java` (+ findByKind)
