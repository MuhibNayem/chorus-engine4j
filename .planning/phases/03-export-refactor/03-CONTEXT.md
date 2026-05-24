# Phase 3 Context: Export Refactor

## Current State

### ExportService.java
- Uses `CompletableFuture.runAsync()` — fire-and-forget, jobs lost on JVM restart
- PARQUET format falls back to JSON with `.parquet` extension (line 111–113)
- Queries only PostgreSQL `JdbcTemplate`; ignores ClickHouse span data
- `queryFilter` is stored in `ExportJob` but **never applied** in the query — exports ALL rows for the tenant
- `resolveTableName()` maps "spans" → "spans", but PostgreSQL `spans` table is shadowed by ClickHouse `ch_spans`
- Cross-tenant leak: `tenant_id` column does not exist on `runs`, `spans`, `llm_calls`, `tool_calls`, `metric_snapshots`

### ExportJob.java
- Record with `jobId`, `tenantId`, `userId`, `name`, `resourceType`, `queryFilter`, `format`, `destination`, `destinationPath`, `status`, `totalRecords`, `fileSizeBytes`, `errorMessage`, `startedAt`, `finishedAt`, `createdAt`
- Enums: `Status {PENDING, RUNNING, COMPLETED, FAILED, CANCELLED}`, `Format {JSON, CSV, PARQUET}`, `Destination {FILE, S3}`

### ExportJobRepository.java
- Pure JDBC with `ON CONFLICT` upsert
- `findPending()` returns all PENDING jobs (no locking)
- No retry tracking, no sub-job support

### Database Schema
- **V1:** `runs`, `spans`, `llm_calls`, `tool_calls`, `metric_snapshots` — **no tenant_id**
- **V5:** `export_jobs`, `tenants`, `users`, `roles` — have tenant_id
- **ClickHouse (V1__init_schema.sql):** `ch_spans`, `ch_llm_calls`, `ch_tool_calls` — **no tenant_id**, scoped by `run_id`

### Build Dependencies
- No Parquet library
- No AWS SDK
- `com.clickhouse:clickhouse-jdbc:0.7.1` already present

### Data Sources
- `chorusObserveDataSource` → PostgreSQL (primary)
- `chorusObserveClickHouseDataSource` → ClickHouse (optional, configured via `chorus.observe.clickhouse.*`)

### TenantContext
- ThreadLocal set by `JwtAuthFilter` and `ApiKeyAuthFilter`
- `OtlpIngestionService` does **not** read `TenantContext` — runs created without tenant association

## Target State

1. **Tenant isolation:** `tenant_id` on `runs` and `metric_snapshots`; export queries use proper JOINs/filters
2. **Real Parquet:** `carpet-record` generates typed Parquet files with embedded schema version metadata
3. **S3/MinIO:** AWS SDK v2 with custom endpoint; encrypted credentials in `export_configs`
4. **Persistent scheduler:** `@Scheduled` polling with `SKIP LOCKED`, retry, orphan recovery
5. **Multi-type exports:** Sub-jobs per data type (span, metric) with independent tracking

## Key Files to Modify

- `chorus-observe-server/build.gradle.kts`
- `chorus-observe-server/src/main/resources/db/migration/V8__export_refactor.sql`
- `chorus-observe-server/src/main/java/com/chorus/observe/model/Run.java`
- `chorus-observe-server/src/main/java/com/chorus/observe/model/MetricSnapshot.java`
- `chorus-observe-server/src/main/java/com/chorus/observe/model/ExportJob.java`
- `chorus-observe-server/src/main/java/com/chorus/observe/model/ExportConfig.java`
- `chorus-observe-server/src/main/java/com/chorus/observe/persistence/RunRepository.java`
- `chorus-observe-server/src/main/java/com/chorus/observe/persistence/MetricRepository.java`
- `chorus-observe-server/src/main/java/com/chorus/observe/persistence/ExportJobRepository.java`
- `chorus-observe-server/src/main/java/com/chorus/observe/persistence/ExportConfigRepository.java`
- `chorus-observe-server/src/main/java/com/chorus/observe/service/OtlpIngestionService.java`
- `chorus-observe-server/src/main/java/com/chorus/observe/export/ExportService.java`
- `chorus-observe-server/src/main/java/com/chorus/observe/export/ParquetExportWriter.java`
- `chorus-observe-server/src/main/java/com/chorus/observe/export/SchemaRegistry.java`
- `chorus-observe-server/src/main/java/com/chorus/observe/export/S3ExportClient.java`
- `chorus-observe-server/src/main/java/com/chorus/observe/export/CredentialEncryptionService.java`
- `chorus-observe-server/src/main/java/com/chorus/observe/export/ExportJobScheduler.java`
- `chorus-observe-server/src/main/java/com/chorus/observe/api/ExportController.java`
- `chorus-observe-server/src/main/java/com/chorus/observe/api/ExportConfigController.java`
- `chorus-observe-server/src/main/java/com/chorus/observe/config/ChorusObserveProperties.java`
- `chorus-observe-server/src/main/java/com/chorus/observe/config/ChorusObserveAutoConfiguration.java`
