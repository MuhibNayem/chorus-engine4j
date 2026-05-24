# Phase 3 Discussion Log: Export Refactor

**Date:** 2026-05-23
**Phase:** 3 — Export Refactor
**Requirements:** EXP-01, EXP-02, EXP-03, EXP-04, EXP-05, EXP-06

---

## Area 1: Parquet Schema Design

**Decision:** Fixed typed schema + schema registry with compatibility checks

- Rejected dynamic schema (no type safety, complex mapping)
- Rejected unified OTLP-like schema (too sparse, larger files)
- **Chosen:** One Java record per exported entity type. Schema registry tracks versions and enforces compatibility (FULL, BACKWARD, NONE).
- Schema version embedded in Parquet file metadata as key `schema_version`.

## Area 2: S3 Credentials Model

**Decision:** Tenant-level `export_config` table with AES-256-GCM encryption

- Rejected environment-variable-only (not per-tenant)
- Rejected AWS Secrets Manager (adds cloud dependency)
- **Chosen:** `export_configs` table per tenant with AES-256-GCM encrypted credentials. Master key from `chorus.observe.export.encryption.master-key` property.

## Area 3: Export Executor Model

**Decision:** Persistent polling scheduler with node-pinned job distribution

- Rejected fire-and-forget (jobs lost on restart)
- Rejected leader election (SPOF)
- **Chosen:** `@Scheduled` method polls every 30s. Nodes claim jobs via `SELECT FOR UPDATE SKIP LOCKED`. Orphan recovery on startup.
- Retry: 3 retries with exponential backoff (5s → 20s → 80s), then FAILED.

## Area 4: Parquet Resource Scope

**Decision:** Spans/traces + metrics (extensible to logs)

- Rejected spans-only (too limited for enterprise)
- **Chosen:** All three pillars. Spans in ClickHouse, metrics in PostgreSQL. Separate Parquet schema per type. Multi-type export jobs create sub-jobs per type for independent scheduling and failure tracking.
- Note: Logs entity does not yet exist in codebase; architecture is extensible for when it is added.

## Open Questions Resolved

| Question | Answer |
|----------|--------|
| carpet-record version | Use `com.jerolba:carpet-record:0.6.1` (latest stable; requirement mentioned 0.7.1 which is not yet published) |
| tenant_id on observability tables | Add to `runs` (root of hierarchy) and `metric_snapshots`. Spans/llm_calls/tool_calls scoped via `run_id` JOIN. |
| ClickHouse vs PostgreSQL for span export | Query ClickHouse when `spanStore != postgresql`, else PostgreSQL. ExportService receives both data sources. |
| H2 test compatibility for SKIP LOCKED | Use dialect-aware query: `SKIP LOCKED` for PostgreSQL, plain `FOR UPDATE` for H2. |
