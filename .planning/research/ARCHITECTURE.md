# Architecture: Enterprise Feature Parity Integration Analysis

**Project:** chorus-engine4j
**Milestone:** Enterprise Feature Parity
**Researched:** 2026-05-23
**Confidence:** HIGH (based on direct source code review of all integration points)

---

## Integration Map Summary

Eight enterprise features integrate with the existing architecture at varying depths. The existing system has clean extension seams — filter chain ordering, `@ConditionalOnMissingBean` auto-configuration, `List<NotificationDispatcher>` constructor injection, `ExportJob.Format`/`Destination` enums — that make several of these additions low-risk. The highest-risk changes are those that touch the filter chain ordering, modify existing records/repositories, or refactor ExportService internals.

---

## Feature 1: SSO / SAML

### New Components

| File | Location | Purpose |
|------|----------|---------|
| `SamlController.java` | `chorus-observe-server/.../api/` | ACS endpoint: `POST /api/v1/auth/saml/acs` receives assertion, validates, calls `JwtTokenService.generate()`, returns JWT |
| `SamlAssertionValidator.java` | `chorus-observe-server/.../security/` | Validates SAML assertion signature using IdP public certificate; wraps OpenSAML or Spring Security SAML2 |
| `SamlConfiguration.java` | `chorus-observe-server/.../model/` | Value record: `entityId`, `idpMetadataUrl`, `acsUrl`, `certificate`, `tenantId` |
| `SamlConfigurationRepository.java` | `chorus-observe-server/.../persistence/` | JDBC repository: load config by `tenantId` and `entityId` |
| `V7__saml_scim.sql` | `chorus-observe-server/.../db/migration/` | DDL for `saml_configurations` and `scim_tokens` tables; User table SCIM columns |

### Modified Components

| File | Change |
|------|--------|
| `JwtAuthFilter.java` (lines 30-35) | Add `/api/v1/auth/saml/` to `PUBLIC_PATHS` set — assertion POST cannot require auth |
| `AuthController.java` | No structural change needed; `SamlController` can be a separate `@RestController` at `/api/v1/auth/saml` |
| `ChorusObserveAutoConfiguration.java` | Register `SamlController` and `SamlAssertionValidator` beans; conditionally enable on `chorus.saml.enabled=true` |

### DB Changes

New table `saml_configurations`:
```sql
CREATE TABLE saml_configurations (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id       VARCHAR(64) NOT NULL REFERENCES tenants(tenant_id),
    entity_id       VARCHAR(512) NOT NULL,
    idp_metadata_url VARCHAR(1024),
    idp_certificate TEXT NOT NULL,
    acs_url         VARCHAR(512) NOT NULL,
    enabled         BOOLEAN NOT NULL DEFAULT true,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (tenant_id, entity_id)
);
```

### Integration Risk

**MEDIUM.** The critical path is: `PUBLIC_PATHS` addition in `JwtAuthFilter` (miss this and SAML assertions get 401), then correct `JwtTokenService.generate()` call from the ACS endpoint. The OpenSAML dependency adds ~10MB to the classpath and is known to conflict with older XML parsers — verify exclusions. The ACS endpoint must be HTTPS in production; local dev needs a self-signed cert or SP-initiated mock. Risk is contained because no existing auth flow is modified — this is additive-only.

---

## Feature 2: SCIM v2 User Provisioning

### New Components

| File | Location | Purpose |
|------|----------|---------|
| `ScimController.java` | `chorus-observe-server/.../api/` | `GET/POST/PUT/DELETE /scim/v2/Users`, `GET/POST /scim/v2/Groups` |
| `ScimAuthFilter.java` | `chorus-observe-server/.../security/` | Validates `Authorization: Bearer <scim-token>` against `scim_tokens` table; sets `TenantContext` |
| `ScimUserMapper.java` | `chorus-observe-server/.../scim/` | Maps between SCIM `User` JSON schema and internal `User` record |
| `ScimTokenRepository.java` | `chorus-observe-server/.../persistence/` | JDBC: load token by hash, check expiry, resolve tenant |
| (migration in V7) | | `scim_tokens` table |

### Modified Components

| File | Change |
|------|--------|
| `JwtAuthFilter.java` | Add `/scim/v2/` to `PUBLIC_PATHS` — SCIM requests carry their own token, not a chorus JWT |
| `ChorusObserveAutoConfiguration.java` | Register `ScimAuthFilter` as `FilterRegistrationBean` at order 2 (between `ApiKeyAuthFilter`=1 and `JwtAuthFilter`=3); register `ScimController` bean |
| `User.java` (record) | Add fields: `externalId` (String, nullable), `scimManaged` (boolean), `lastSyncedAt` (Instant, nullable) |
| `UserRepository.java` | Update `save()` SQL to include new SCIM columns; add `findByExternalId(tenantId, externalId)` method |
| `NotificationChannel.java` (`ChannelType` enum) | No change for SCIM specifically |

### DB Changes

Extend `users` table (in V7 migration):
```sql
ALTER TABLE users ADD COLUMN external_id    VARCHAR(256);
ALTER TABLE users ADD COLUMN scim_managed   BOOLEAN NOT NULL DEFAULT false;
ALTER TABLE users ADD COLUMN last_synced_at TIMESTAMPTZ;
CREATE UNIQUE INDEX users_tenant_external_id ON users(tenant_id, external_id) WHERE external_id IS NOT NULL;
```

New table `scim_tokens`:
```sql
CREATE TABLE scim_tokens (
    id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id   VARCHAR(64) NOT NULL REFERENCES tenants(tenant_id),
    token_hash  VARCHAR(128) NOT NULL UNIQUE,
    description VARCHAR(256),
    expires_at  TIMESTAMPTZ,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);
```

### Integration Risk

**HIGH.** This is the highest-risk feature. Reasons:

1. `User` is a Java record — adding fields changes the canonical constructor. Every `new User(...)` call site (including `UserRepository.rowMapper`, `UserService.createUser`) must be updated simultaneously or the code won't compile.
2. `UserRepository.save()` has a hardcoded column list in the INSERT SQL — missing the new columns causes runtime `NOT NULL` constraint failures if `scim_managed` is non-nullable.
3. `ScimAuthFilter` must be wired at order 2 in the filter chain — if wired after `JwtAuthFilter` (order 3), SCIM requests fail because JwtAuthFilter returns 401 before SCIM filter runs.
4. The SCIM spec requires PATCH with RFC 7396 JSON Merge Patch — partial update semantics that the existing JDBC repositories don't support natively.

Mitigation: add SCIM columns as nullable first, compile-check all `User` construction sites, implement SCIM filter chain order test.

---

## Feature 3: Automated Eval Generation (LLM-Generated Test Cases)

### New Components

| File | Location | Purpose |
|------|----------|---------|
| `AutoEvalGenerationService.java` | `chorus-observe-server/.../service/` | Reads `LlmCall` records from `SpanRepository`/`RunRepository`, invokes `AgentInvoker` to generate expected output, stores as `DatasetEntry` |
| `EvalGenerationRequest.java` | `chorus-observe-server/.../model/` | DTO: `datasetId`, `runIds[]`, `evaluatorId`, `sampleSize`, `generationPrompt` |

### Modified Components

| File | Change |
|------|--------|
| `EvaluatorController.java` | Add: `POST /api/v1/datasets/{datasetId}/generate-evals` — delegates to `AutoEvalGenerationService`; returns job status |
| `DatasetService.java` | `createFromTraces()` already exists — `AutoEvalGenerationService` calls it for the input samples, then augments with LLM-generated expectations |
| `ChorusObserveAutoConfiguration.java` | Register `AutoEvalGenerationService` bean; inject existing `AgentInvoker` + `DatasetService` + `SpanRepository` |

### DB Changes

No new tables. Existing `datasets` and `dataset_entries` tables accommodate generated test cases. Consider adding a `generation_metadata` JSONB column to `dataset_entries` if tracking provenance of generated expectations is required — but this is optional and can be deferred.

### Integration Risk

**LOW.** This is a pure addition. The hard dependency is `AgentInvoker` being available (it is — wired in auto-configuration). The LLM call for generation goes through the existing `AgentInvokerJudgeScorer` pattern. The only coupling risk is if the generation prompt produces outputs that don't fit `DatasetEntry` structure — validate with schema checks before persist. No existing flow is modified.

---

## Feature 4: Real Parquet Export + S3 Destination

### New Components

| File | Location | Purpose |
|------|----------|---------|
| `ExportFormatWriter.java` (interface) | `chorus-observe-server/.../export/` | `write(List<ExportRecord> records, OutputStream out)` — format strategy |
| `JsonFormatWriter.java` | `chorus-observe-server/.../export/` | Extracts existing JSON write logic from `ExportService` |
| `CsvFormatWriter.java` | `chorus-observe-server/.../export/` | Extracts existing CSV write logic from `ExportService` |
| `ParquetFormatWriter.java` | `chorus-observe-server/.../export/` | Implements real Parquet write using Apache Parquet library (`parquet-hadoop` or `parquet-avro`) |
| `ExportDestinationStrategy.java` (interface) | `chorus-observe-server/.../export/` | `deliver(ExportJob job, Path tempFile)` — destination strategy |
| `FileDestinationStrategy.java` | `chorus-observe-server/.../export/` | Extracts existing local file write from `ExportService` |
| `S3DestinationStrategy.java` | `chorus-observe-server/.../export/` | Uploads `tempFile` to S3 using AWS SDK v2; reads bucket/prefix from `ExportJob.config()` |

### Modified Components

| File | Change |
|------|--------|
| `ExportService.java` | Refactor `executeExport()`: replace inline format switch with `ExportFormatWriter` dispatch; replace hardcoded `Path.of("exports")` write with `ExportDestinationStrategy` dispatch. The method signature does not change — only internals. |
| `ChorusObserveAutoConfiguration.java` | Register `ParquetFormatWriter`, `S3DestinationStrategy` beans; wire `List<ExportFormatWriter>` and `List<ExportDestinationStrategy>` into refactored `ExportService` |
| `build.gradle` (chorus-observe-server) | Add `implementation("org.apache.parquet:parquet-hadoop:1.14.x")` and `implementation("software.amazon.awssdk:s3:2.x")` |

### DB Changes

None. `ExportJob.Format.PARQUET` and `ExportJob.Destination.S3` already exist in the enum and the `export_jobs.format`/`destination` columns already store them.

### Integration Risk

**MEDIUM.** The refactor touches `ExportService.executeExport()` which is the core export path — regression risk for existing JSON and CSV exports. Apache Parquet's `parquet-hadoop` dependency brings Hadoop classpath pollution (mitigate with `parquet-avro` or `parquet-column` standalone). AWS SDK v2 is large — use BOM to manage transitive dependencies. The strategy pattern refactor should be done atomically with comprehensive tests before introducing the new writers. Risk is higher during the refactor window but stable afterward.

---

## Feature 5: Microsoft Teams Dispatcher

### New Components

| File | Location | Purpose |
|------|----------|---------|
| `TeamsDispatcher.java` | `chorus-observe-server/.../notification/` | Implements `NotificationDispatcher`; `channelType()` returns `"TEAMS"`; POSTs Adaptive Card JSON to `webhookUrl` from `channel.config()` |

### Modified Components

| File | Change |
|------|--------|
| `NotificationChannel.java` | Add `TEAMS` to `ChannelType` enum. `VARCHAR(32)` DB column already accommodates this. |
| `ChorusObserveAutoConfiguration.java` | Add `@Bean TeamsDispatcher teamsDispatcher()` — auto-picked up by `NotificationService` via `List<NotificationDispatcher>` injection |

### DB Changes

None. `notification_channels.channel_type VARCHAR(32)` already exists and accommodates `TEAMS`.

### Integration Risk

**LOW.** This is the lowest-risk feature in the set. The `NotificationService` constructor injection pattern (`List<NotificationDispatcher>`) means adding a new dispatcher is automatic. The only failure mode is a misconfigured `webhookUrl` in a channel's config JSONB — add validation in `TeamsDispatcher.dispatch()` with a clear error message. Teams webhook format (Adaptive Cards or legacy O365 connector format) should be verified against Microsoft's current API — the legacy connector format is being deprecated in favor of workflows-based webhooks.

---

## Feature 6: Hallucination Evaluator

### New Components

| File | Location | Purpose |
|------|----------|---------|
| `HallucinationScorer.java` | `chorus-engine-guardrails/.../evaluators/` | Implements LLM-judge scoring: given `(context, generated_output)`, invokes LLM to score factual consistency; returns 0.0-1.0 |
| `HallucinationEvaluatorService.java` | `chorus-observe-server/.../service/` | Wraps `HallucinationScorer`; called by `EvaluatorService.evaluateRun()` when `kind="hallucination"` |
| `V8__hallucination_evaluator_seed.sql` | `chorus-observe-server/.../db/migration/` | INSERT seed row into `evaluators` table: `kind='hallucination'`, `name='Hallucination Check'` |

### Modified Components

| File | Change |
|------|--------|
| `EvaluatorService.java` | Extend `evaluateRun()`: switch on `evaluator.kind()` — add `case "hallucination" -> hallucinationEvaluatorService.score(runId, evaluator)` instead of returning hardcoded `0.0` |
| `ChorusObserveAutoConfiguration.java` | Register `HallucinationScorer` and `HallucinationEvaluatorService` beans |

### DB Changes

New seed row in `evaluators` (V8 migration):
```sql
INSERT INTO evaluators (evaluator_id, name, kind, description, config, tenant_id, created_at, updated_at)
VALUES ('ev_hallucination', 'Hallucination Check', 'hallucination',
        'LLM-judge scoring of factual consistency vs. retrieved context',
        '{"model": "default", "threshold": 0.7}'::jsonb,
        NULL, now(), now())
ON CONFLICT (evaluator_id) DO NOTHING;
```

**Note on abstraction boundary:** The `HallucinationScorer` lives in `chorus-engine-guardrails` for logic reuse (it can also be wired as a tier-3 `Guardrail` for runtime blocking), but it is exposed via the `EvaluatorController` as `kind="hallucination"` — not wired into `TieredGuardrailEngine` for the scoring path. The guardrail wiring is a separate, optional future concern.

### Integration Risk

**LOW-MEDIUM.** The `EvaluatorService.evaluateRun()` change is surgical — add one case to the evaluator kind dispatch. Risk: if `evaluateRun()` currently has no switch (it stubs `0.0` for all kinds), introducing a switch requires handling all existing kinds too — verify no regressions for `helpfulness`, `groundedness`, `latency_sla`, `no_pii` evaluator kinds. The LLM invocation from the scorer requires an `AgentInvoker` reference to be injected into `HallucinationEvaluatorService` — confirm that the `AgentInvoker` bean is available in `chorus-observe-server`'s context (it is, wired in `ChorusObserveAutoConfiguration`).

---

## Feature 7: CI/CD Eval Gate

### New Components

| File | Location | Purpose |
|------|----------|---------|
| `EvalGateController.java` | `chorus-observe-server/.../api/` | `POST /api/v1/eval-gates/{gateId}/run` — executes eval run against a gate config, returns 200 (pass) or 417 (fail) |
| `EvalGateService.java` | `chorus-observe-server/.../service/` | Calls `EvalService.submitEvalRun()`, polls for completion, compares score vs. threshold; returns pass/fail |
| `EvalGate.java` | `chorus-observe-server/.../model/` | Record: `gateId`, `name`, `evaluatorId`, `datasetId`, `threshold`, `tenantId` |
| `EvalGateRepository.java` | `chorus-observe-server/.../persistence/` | JDBC: CRUD for `eval_gates` table |
| `V9__eval_gates.sql` | `chorus-observe-server/.../db/migration/` | DDL for `eval_gates` table |
| `.github/actions/chorus-eval-gate/action.yml` | repo root | Composite GitHub Action: calls the REST endpoint, reads exit code |

### Modified Components

| File | Change |
|------|--------|
| `EvalService.java` | Expose `submitEvalRun()` as a public method (verify current visibility) — `EvalGateService` calls it |
| `ChorusObserveAutoConfiguration.java` | Register `EvalGateController`, `EvalGateService`, `EvalGateRepository` beans |

### DB Changes

New table `eval_gates`:
```sql
CREATE TABLE eval_gates (
    gate_id      VARCHAR(64) PRIMARY KEY,
    tenant_id    VARCHAR(64) NOT NULL REFERENCES tenants(tenant_id),
    name         VARCHAR(256) NOT NULL,
    evaluator_id VARCHAR(64) NOT NULL REFERENCES evaluators(evaluator_id),
    dataset_id   UUID REFERENCES datasets(id),
    threshold    DOUBLE PRECISION NOT NULL DEFAULT 0.8,
    enabled      BOOLEAN NOT NULL DEFAULT true,
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at   TIMESTAMPTZ NOT NULL DEFAULT now()
);
```

### Integration Risk

**LOW-MEDIUM.** The gate endpoint composes existing services — low structural risk. The polling loop inside `EvalGateService` needs a timeout and backoff to avoid infinite blocking on slow evals. The GitHub Action must handle auth (API key or a service account JWT) — define a `CHORUS_API_KEY` secret in the repo. The 417 HTTP status for threshold failure is unconventional — verify that GitHub Actions treats any non-2xx as failure (it does by default with `curl -f`).

---

## Feature 8: Frontend Pages (chorus-studio)

### New Components

Six new Next.js 14 app-router pages and supporting components:

| Route | File | Purpose |
|-------|------|---------|
| `/sso` | `app/sso/page.tsx` | SAML SSO configuration — IdP metadata URL, entity ID, test connection |
| `/scim` | `app/scim/page.tsx` | SCIM token management — generate, list, revoke provisioning tokens |
| `/eval-gates` | `app/eval-gates/page.tsx` | Eval gate CRUD — list gates, create, set threshold, view last run result |
| `/eval-gates/[gateId]` | `app/eval-gates/[gateId]/page.tsx` | Gate detail — run history, pass/fail trend |
| `/evals/generate` | `app/evals/generate/page.tsx` | Automated eval generation — select dataset + runs, trigger LLM generation |
| `/settings/export` | `app/settings/export/page.tsx` | Export settings — Parquet format selection, S3 bucket/prefix config |

Supporting component directories to create:
- `components/sso/` — SamlConfigForm, ConnectionTestResult
- `components/scim/` — ScimTokenTable, GenerateTokenDialog
- `components/eval-gates/` — EvalGateTable, EvalGateForm, GateRunResult

### Modified Components

| File | Change |
|------|--------|
| `lib/api.ts` | Add API client methods: `samlApi`, `scimApi`, `evalGateApi`, additions to `api` object for Parquet export and eval generation endpoints |
| `components/shell/AppShell.tsx` (or nav config) | Add nav items for SSO, SCIM, Eval Gates, Export Settings |
| `types/index.ts` (or equivalent) | Add TypeScript types: `SamlConfig`, `ScimToken`, `EvalGate`, `EvalGateRun` |

### DB Changes

None (frontend only).

### Integration Risk

**LOW.** All pages call existing or new REST endpoints — they cannot break existing functionality. The main risk is API contract mismatches (TypeScript types diverging from actual JSON). Use the OpenAPI spec (available at `/v3/api-docs`) to generate types, or keep types manually in sync. Auth follows existing pattern: `fetchJson()` with Bearer token from localStorage.

---

## Recommended Build Order

### Phase ordering rationale

The order respects three constraints:
1. **Security chain first** — SSO and SCIM touch the filter chain; they must be stable before other features can assume auth works correctly.
2. **DB migrations are serial** — Flyway applies V7 before V8 before V9; features must ship in migration number order.
3. **Additive before refactor** — Low-risk additive features (Teams, Hallucination seed) before the moderate-risk refactor (Parquet/S3 ExportService).
4. **Backend before frontend** — Each frontend page has a backend API dependency.

### Build order

```
Phase 1 — Foundation (security + user model)
  1a. SSO / SAML
      - V7 migration (saml_configurations + scim_tokens + user SCIM columns)
      - SamlAssertionValidator, SamlController, SamlConfigurationRepository
      - Modify JwtAuthFilter PUBLIC_PATHS, register beans in AutoConfiguration
  1b. SCIM v2
      - Depends on V7 migration (same file as SAML)
      - Modify User record + UserRepository (SCIM columns)
      - ScimAuthFilter, ScimController, ScimTokenRepository
      - Wire ScimAuthFilter at order 2 in AutoConfiguration

Phase 2 — Notifications + Evaluator ecosystem (additive, zero DB risk)
  2a. Teams Dispatcher
      - Add TEAMS to ChannelType enum
      - TeamsDispatcher.java
      - Bean in AutoConfiguration
  2b. Hallucination Evaluator
      - HallucinationScorer in chorus-engine-guardrails
      - HallucinationEvaluatorService in chorus-observe-server
      - V8 migration (seed row)
      - Extend EvaluatorService.evaluateRun() kind dispatch

Phase 3 — Export refactor (moderate risk, isolated to export package)
  3a. Parquet + S3 Export
      - ExportFormatWriter + ExportDestinationStrategy interfaces
      - Extract existing JSON/CSV logic into JsonFormatWriter, CsvFormatWriter
      - ParquetFormatWriter, S3DestinationStrategy
      - Refactor ExportService.executeExport() internals
      - Add parquet-hadoop + AWS SDK v2 dependencies
      - Regression test all three formats before merging

Phase 4 — Eval intelligence (new services composing existing)
  4a. Automated Eval Generation
      - AutoEvalGenerationService
      - POST /api/v1/datasets/{id}/generate-evals endpoint
  4b. CI/CD Eval Gate
      - Depends on hallucination evaluator being available (Phase 2b)
      - V9 migration (eval_gates table)
      - EvalGate, EvalGateRepository, EvalGateService, EvalGateController
      - .github/actions/chorus-eval-gate/action.yml

Phase 5 — Frontend (parallel with Phase 4 or after)
  5a. SSO + SCIM pages (depends on Phase 1 APIs)
  5b. Eval Gates + Eval Generation pages (depends on Phase 4 APIs)
  5c. Export Settings page (depends on Phase 3 APIs)
  5d. Nav wiring + type definitions
```

### Why this order

- **Phase 1 first:** SCIM and SSO both write to V7 migration — combining them avoids out-of-order migration conflicts. SSO must be stable for SCIM to rely on the same token infrastructure.
- **Phase 2 before Phase 3:** Teams and Hallucination are additive with no refactor risk — ship these to gain confidence before the riskier ExportService refactor.
- **Phase 3 isolated:** The export refactor is the highest regression risk (touches all export paths). By doing it after Phase 2, the codebase is in a stable state and the change can be tested in isolation.
- **Phase 4 after Phase 2b:** CI/CD gate uses the hallucination evaluator as a scoring backend — `EvalGateService` depends on `EvaluatorService.evaluateRun()` being extended with `kind="hallucination"`.
- **Phase 5 parallel-capable:** Frontend pages have no backend coupling beyond API contract. Pages can be built against the backend as each phase stabilizes. SSO/SCIM pages can start as soon as Phase 1 merges.

---

## Cross-Cutting Concerns

### Filter Chain Order (Critical)

```
Order 1: ApiKeyAuthFilter     (existing — no change)
Order 1: TracingFilter        (existing — no change)
Order 2: RateLimitFilter      (existing — no change)
Order 2: ScimAuthFilter       (NEW — must be before JwtAuthFilter)
Order 3: JwtAuthFilter        (existing — PUBLIC_PATHS modified)
Order 4: RbacAuthorizationFilter (existing — no change)
```

ScimAuthFilter at order 2 ensures SCIM requests are authenticated before JwtAuthFilter sees them. Adding it after order 3 would cause JwtAuthFilter to 401 SCIM requests first.

### Migration Numbering

Existing migrations: V1-V6. Proposed new migrations:
- `V7__saml_scim.sql` — SAML + SCIM tables + User columns
- `V8__hallucination_evaluator_seed.sql` — Evaluator seed row
- `V9__eval_gates.sql` — Eval gates table

These must be applied in order. If parallel development creates V7 conflicts, use sequential numbering with feature branches rebasing onto main before merge.

### AutoConfiguration Registration

`ChorusObserveAutoConfiguration.java` is ~1243 lines. All new beans register here with `@ConditionalOnMissingBean` so consuming applications can override. Estimated additions: ~80-120 lines for all 8 features. Consider extracting feature-specific `@Configuration` classes (e.g., `SamlAutoConfiguration`, `ScimAutoConfiguration`) and importing them from the main auto-configuration class to keep the file manageable.

### Dependency Additions

| Dependency | Feature | Risk |
|------------|---------|------|
| OpenSAML 4.x or `spring-security-saml2-service-provider` | SAML | XML parser conflicts; test exclusions |
| `parquet-avro` or `parquet-hadoop` | Parquet export | Hadoop classpath pollution; use `parquet-avro` with exclusions |
| `software.amazon.awssdk:s3` | S3 export | Large transitive tree; use BOM |
| No new deps | Teams, Hallucination, Eval Gate, CI/CD gate | Clean |

---

## Sources

- Direct code review: `JwtAuthFilter.java`, `NotificationDispatcher.java`, `NotificationService.java`, `ExportService.java`, `ExportJob.java`, `User.java`, `UserRepository.java`, `EvaluatorService.java`, `EvalService.java`, `ChorusObserveAutoConfiguration.java`, `SlackDispatcher.java`, `TieredGuardrailEngine.java`, `AuthController.java`, `chorus-studio/lib/api.ts`
- DB schema: `V5__enterprise_rbac_audit_retention_export_notifications_dashboards.sql`, `V6__agents_models_evaluators.sql`
- Spring Boot filter chain ordering: official documentation (HIGH confidence)
- Flyway serial migration semantics: official documentation (HIGH confidence)
- Microsoft Teams webhook deprecation (legacy connectors): Microsoft documentation (MEDIUM confidence — verify current status before implementing)
