# Phase 3 Summary: Export Refactor

**Status:** Planning complete — ready for execution
**Requirements:** EXP-01, EXP-02, EXP-03, EXP-04, EXP-05, EXP-06

---

## Decisions Locked

| Area | Decision |
|------|----------|
| Parquet Schema | Fixed typed Java records + SchemaRegistry with compatibility checks |
| S3 Credentials | Tenant-level `export_configs` table, AES-256-GCM with configurable master key |
| Executor Model | Persistent `@Scheduled` polling, node-pinned via `SELECT FOR UPDATE SKIP LOCKED` |
| Retry Policy | 3 retries, exponential backoff (5s → 20s → 80s) |
| Resource Scope | Spans (ClickHouse/PostgreSQL) + Metrics (PostgreSQL), extensible to Logs |
| Multi-type Jobs | Sub-jobs per type with independent scheduling and failure tracking |

## Plans

| Plan | Wave | Goal | Requirements |
|------|------|------|-------------|
| [PLAN-03-01](PLAN-03-01.md) | 1 | Schema & Domain Models — add tenant_id to runs/metric_snapshots, create export_configs, update models/repos | EXP-01 |
| [PLAN-03-02](PLAN-03-02.md) | 2 | Parquet Export Engine — carpet-record, typed schemas, real Parquet generation, queryFilter application | EXP-02, EXP-03, EXP-04 |
| [PLAN-03-03](PLAN-03-03.md) | 3 | S3/MinIO Destination — AWS SDK v2, credential encryption, upload, admin API | EXP-05 |
| [PLAN-03-04](PLAN-03-04.md) | 4 | Persistent Scheduler — @Scheduled polling, row locking, retry, orphan recovery | EXP-01, EXP-05 |
| [PLAN-03-05](PLAN-03-05.md) | 5 | API & Integration Tests — download endpoint, cross-tenant test, Parquet readability, encryption, scheduler | EXP-01–EXP-06 |

## Dependencies

```
03-01 (Schema) ─┬─→ 03-02 (Parquet) ─┬─→ 03-04 (Scheduler) ─┐
                │                     │                       ├──→ 03-05 (Tests)
                └─────────────────────┴─→ 03-03 (S3) ─────────┘
```

## New Files (estimated)

- `db/migration/V8__export_refactor.sql`
- `model/ExportConfig.java`
- `export/ParquetExportWriter.java`
- `export/SchemaRegistry.java`
- `export/SpanExportRecord.java`
- `export/MetricExportRecord.java`
- `export/ExportQueryBuilder.java`
- `export/S3ExportClient.java`
- `export/CredentialEncryptionService.java`
- `export/ExportJobScheduler.java`
- `persistence/ExportConfigRepository.java`
- `api/ExportConfigController.java`
- 4 test files

## Modified Files (estimated)

- `build.gradle.kts` (+ carpet-record, + AWS SDK v2)
- `model/Run.java` (+ tenantId)
- `model/MetricSnapshot.java` (+ tenantId)
- `model/ExportJob.java` (+ retryCount, nextRetryAt, parentJobId)
- `persistence/RunRepository.java` (+ tenantId)
- `persistence/MetricRepository.java` (+ tenantId)
- `persistence/ExportJobRepository.java` (+ locking, retry)
- `persistence/SpanRepository.java` (+ tenant-scoped query)
- `service/OtlpIngestionService.java` (+ TenantContext)
- `export/ExportService.java` (complete rewrite)
- `api/ExportController.java` (+ download endpoint)
- `config/ChorusObserveProperties.java` (+ Export encryptionMasterKey)
- `config/ChorusObserveAutoConfiguration.java` (+ new beans)
