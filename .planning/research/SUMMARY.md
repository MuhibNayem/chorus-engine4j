# Research Summary: Enterprise Feature Parity

**Synthesized:** 2026-05-23
**Sources:** STACK.md, FEATURES.md, ARCHITECTURE.md, PITFALLS.md
**Overall Confidence:** HIGH (all four research files grounded in direct codebase review + verified library versions)

---

## Executive Summary

Chorus Observe is a Spring Boot 4.0 / Java 25 LLM observability platform being extended with eight enterprise-grade features: SSO (OAuth2 + SAML 2.0), SCIM v2 provisioning, automated eval generation, CI/CD eval gate, real Parquet export, S3 destination, Microsoft Teams alerting, and supporting frontend pages. The platform has a mature, extension-friendly architecture — clean filter chain ordering, pluggable dispatcher lists, and strategy-based export — which makes most of these additions low-risk rather than invasive rewrites. However, the codebase carries three production-blocking defects that must be fixed before enterprise features ship: an untenanted export query that leaks cross-tenant data, an explicit Spring Security 6.5.0 pin that prevents all SSO work from compiling, and a filter chain gap where SCIM endpoints can either bypass or break authentication.

The most critical dependency is the Spring Security 6.5.0 -> 7.0 migration. The build file explicitly pins spring-security-crypto, spring-security-config, and spring-security-web at 6.5.0, overriding the Boot 4.0.0 BOM which provides Security 7.0.0. The SAML2 and OAuth2 starters require Security 7 APIs at runtime; with the 6.5.0 pin in place they fail silently or crash at startup. This migration must land as Phase 0 before any AUTH work begins — estimated at 1-3 days but with real API-change risk in the existing JWT filter chain. All SSO, SCIM, and downstream eval gate work is blocked on this single prerequisite.

Strategically, the milestone builds in a clear dependency order: security foundation first (auth + SCIM share the same V7 database migration), then additive low-risk features (Teams dispatcher, hallucination evaluator), then the one moderate-risk refactor (ExportService strategy pattern for Parquet + S3), then eval intelligence features (auto-gen, CI gate), and finally frontend pages that can largely build in parallel once backend APIs stabilize. The five highest-priority pitfalls to prevent are: (1) the cross-tenant export query at ExportService line 65, (2) the SAML/OAuth2 RBAC scope bypass via SecurityContextHolder, (3) SCIM-created duplicate users on email match/case mismatch, (4) TenantContext loss in async tasks, and (5) LLM judge non-determinism flapping the CI eval gate.

---

## Stack Additions

### PREREQUISITE — Remove Before Any SSO Work

| Action | Location | Risk |
|--------|----------|------|
| Remove `spring-security-crypto:6.5.0`, `spring-security-config:6.5.0`, `spring-security-web:6.5.0` explicit pins | `chorus-observe-server/build.gradle.kts` lines 47-49 | Boot 4.0.0 BOM then resolves Security 7.0.0; existing JWT filter chain needs inspection for Security 6->7 API breaks. Estimate 1-3 days. |

This is a hard prerequisite blocker. All SSO and SCIM work fails to compile or crashes at runtime until this pin is removed.

### New Library Dependencies

| Library | Version | Feature | Rationale |
|---------|---------|---------|-----------|
| `spring-boot-starter-security-saml2` | `4.0.0` | AUTH-02 SAML | Boot starter wires OpenSAML 5 + Security 7; do not use archived `spring-security-saml2-core` |
| `spring-boot-starter-oauth2-client` | `4.0.0` | AUTH-01 OAuth2 | Handles Google, GitHub, Azure AD OIDC via autoconfiguration |
| `com.unboundid.product.scim2:scim2-sdk-common` | `6.0.0` | AUTH-03 SCIM | Only version with Jackson 3 support (Boot 4 compatible); do NOT use `scim2-sdk-server` (JAX-RS conflict) or any version below 6.0.0 |
| `com.jerolba:carpet-record` | `0.7.1` | COMP-01 Parquet | Hadoop-free Parquet writer for Java Records; no HDFS or Kerberos; supports nested types needed for OTLP spans |
| `software.amazon.awssdk:bom` | `2.44.12` | COMP-02 S3 | AWS SDK v2; per-service artifacts; use Transfer Manager only for files consistently over 100MB |
| `software.amazon.awssdk:s3` | managed by BOM | COMP-02 S3 | Only artifact needed for export-only S3 writes |

### No New Library Required

| Feature | Approach |
|---------|---------|
| Teams alerting (COMP-03) | `RestClient` (already in spring-web on Boot 4 classpath); Power Automate Adaptive Card webhook only |
| Automated eval generation (EVAL-01) | `chorus-engine-llm` + `AgentInvokerJudgeScorer` (already wired); no new LLM framework |
| CI/CD eval gate (COMP-05) | Custom Gradle task (~50-80 lines) + `dorny/test-reporter@v1`; no new runtime deps |
| Hallucination evaluator (COMP-04) | `HallucinationScorer` in `chorus-engine-guardrails`; uses existing `AgentInvoker` |

### Critical: Teams O365 Connector Retirement

Office 365 Connectors (`outlook.office.com/webhook/...` URLs) were retired May 18-22, 2026. These URLs no longer deliver messages. The only valid Teams integration path is Power Automate Workflow webhooks using Adaptive Card JSON payloads. Any code or documentation referencing the legacy connector format must use the Power Automate format instead — no legacy MessageCard format.

---

## Feature Table Stakes

### AUTH-01: OAuth2 / OIDC SSO

| Capability | Must Ship Day 1 | Differentiator | Defer |
|-----------|:--------------:|:--------------:|:-----:|
| Google + GitHub OIDC login | Yes | | |
| Azure AD / Okta OIDC login | Yes | | |
| JIT user provisioning (VIEWER role default) | Yes | | |
| Coexist with existing email/password JWT auth | Yes | | |
| Admin UI to configure IdP per tenant | Yes | | |
| Per-tenant SSO enforcement (block email/password) | | Yes | |
| Attribute mapping (IdP claim -> Chorus role) | | | v2 |

### AUTH-02: SAML 2.0

| Capability | Must Ship Day 1 | Differentiator | Defer |
|-----------|:--------------:|:--------------:|:-----:|
| SP-initiated SAML login (Okta, Azure AD, ADFS) | Yes | | |
| JIT provisioning on assertion success | Yes | | |
| Per-tenant IdP config (entity ID, cert, ACS URL) | Yes | | |
| Assertion replay protection (assertion ID cache) | Yes (security req) | | |
| IdP-initiated SSO | | | v2 |

### AUTH-03: SCIM v2

| Capability | Must Ship Day 1 | Differentiator | Defer |
|-----------|:--------------:|:--------------:|:-----:|
| POST/GET/PUT/PATCH/DELETE /scim/v2/Users | Yes | | |
| SCIM filter query (?filter=userName eq "...") | Yes | | |
| GET /scim/v2/ServiceProviderConfig | Yes | | |
| Bearer token auth separate from user JWT | Yes | | |
| Soft-deactivate on SCIM DELETE (preserve audit trail) | Yes | | |
| Email case normalization at every write path | Yes (prevents P-2/P-3) | | |
| SCIM Groups push for role mapping | | Yes | Defer unless customer requires |

### COMP-01: Real Parquet Export

| Capability | Must Ship Day 1 | Differentiator | Defer |
|-----------|:--------------:|:--------------:|:-----:|
| Fix cross-tenant leak at ExportService.java:65 | Yes (security blocker) | | |
| Fix Parquet stub at ExportService.java:110-114 | Yes | | |
| Schema consistent across export jobs | Yes | | |
| Hive-compatible date partitioning in file path | Yes | | |
| Readable by pandas, DuckDB, Athena | Yes | | |
| Snappy compression, configurable codec | | Yes | |
| Schema version in Parquet file metadata | Yes (schema evolution) | | |

### COMP-02: S3 Export

| Capability | Must Ship Day 1 | Differentiator | Defer |
|-----------|:--------------:|:--------------:|:-----:|
| Upload export to S3 bucket post-write | Yes | | |
| Configurable bucket + prefix per tenant | Yes | | |
| IAM role or env-var credentials | Yes | | |
| Scheduled daily export | Yes | | |
| S3 credentials in tenant-level config, never in job rows | Yes (security req) | | |
| STS AssumeRole cross-account | | Yes | |
| Presigned URL for local file downloads | | Yes | |

### COMP-03: Microsoft Teams Alerting

| Capability | Must Ship Day 1 | Differentiator | Defer |
|-----------|:--------------:|:--------------:|:-----:|
| Power Automate webhook + Adaptive Card payload | Yes | | |
| Webhook URL masked in API responses | Yes (security req) | | |
| Alert routing by tenant (per-channel config) | Yes | | |
| Legacy O365 connector format | DO NOT IMPLEMENT | | |

### EVAL-01: Automated Eval Generation

| Capability | Must Ship Day 1 | Differentiator | Defer |
|-----------|:--------------:|:--------------:|:-----:|
| Sample traces by recency + score diversity | Yes | | |
| LLM-synthesize expected output from (input, actual output) | Yes | | |
| Human review gate (GENERATED -> PENDING_REVIEW -> APPROVED) | Yes — no auto-approval path | | |
| Source trace ID visible alongside proposed case | Yes | | |
| Approved cases land in existing dataset model | Yes | | |
| Coverage clustering (embedding diversity) | | Yes | |
| Failure-first sampling (weight toward low-scoring spans) | | Yes | |

### COMP-05: CI/CD Eval Gate

| Capability | Must Ship Day 1 | Differentiator | Defer |
|-----------|:--------------:|:--------------:|:-----:|
| GitHub Action triggering eval run + polling | Yes | | |
| Configurable thresholds per evaluator in YAML | Yes | | |
| Non-zero exit on regression | Yes | | |
| N-run median score (minimum 3 runs) to prevent flap | Yes (reliability req) | | |
| Separate exit codes: regression (1) vs infra error (2) | Yes | | |
| Per-evaluator thresholds (safety vs factuality) | | Yes | |
| Baseline pinning (compare vs named green run) | | Yes | |

---

## Recommended Build Order

### Phase 0 — Security Migration Prerequisite (1-3 days)

**What:** Remove the three explicit Spring Security 6.5.0 version pins from `chorus-observe-server/build.gradle.kts` (lines 47-49). Let Boot 4.0.0 BOM manage Security to 7.0.0. Audit existing `JwtAuthFilter` and `HttpSecurity` DSL for Security 6->7 API breaks. Regression-test all existing auth paths.

**Why first:** SAML2 and OAuth2 starters require Security 7 APIs. With 6.5.0 pinned, they fail at runtime. Nothing in AUTH-01, AUTH-02, or AUTH-03 can be built until this lands.

**Delivers:** Clean, compile-clean codebase on the supported Security 7 baseline.

**Risk:** MEDIUM-LOW. Use this phase to also audit `RbacAuthorizationFilter` scope-read pattern and plan the SSO adapter filter (mitigation for P-1).

---

### Phase 1 — Security Foundation + User Model (2.5-3.5 weeks)

**What:** SSO (AUTH-01 + AUTH-02) and SCIM (AUTH-03). All three share the V7 Flyway migration (`saml_configurations`, `scim_tokens`, User table SCIM columns). Build sequentially: OAuth2 first, then SAML, then SCIM.

**Rationale:** Enterprise deals are gated on SSO + SCIM. The V7 migration must be a single file to avoid out-of-order Flyway conflicts. SCIM depends on the user model stabilized by JIT provisioning.

**Delivers:** Enterprise authentication parity. Any IdP can provision and authenticate users. SCIM-managed users are consistent with SSO-authenticated users.

**Features:** AUTH-01, AUTH-02, AUTH-03

**Pitfalls to prevent:**
- P-1: Write `SsoScopesAdapterFilter` before testing any protected endpoint. SAML/OAuth2 principals do not set `request.attribute("scopes")`.
- P-2 + P-3: SCIM POST /Users must call `findByEmail` first. Lowercase email at every write path. Database unique index must use `LOWER(email)`.
- P-4: Enable assertion replay cache + configure 2-minute clock skew tolerance on SAML.
- P-5: `ScimAuthFilter` at filter chain Order 2, before `JwtAuthFilter`. Add `/scim/v2/` to a dedicated matcher, not `PUBLIC_PATHS`.

---

### Phase 2 — Low-Risk Additive Features (1-1.5 weeks)

**What:** Microsoft Teams dispatcher (COMP-03) and Hallucination evaluator (COMP-04). Pure additions — no existing flows modified. Teams adds one `ChannelType` enum value and one `NotificationDispatcher`. Hallucination adds one scorer class and extends the evaluator kind dispatch by one case.

**Rationale:** Lowest-risk changes in the milestone. Ship before the moderate-risk ExportService refactor to keep main branch stable and gain integration confidence.

**Delivers:** Teams alerting live. Hallucination scoring available as an evaluator kind.

**Features:** COMP-03, COMP-04

**Pitfalls to prevent:**
- P-10: Teams dispatcher must receive `tenantId` as an explicit parameter — never read `TenantContext` inside an async dispatch lambda.
- P-15: Hallucination scorer must run async post-ingestion only. Never synchronously on the OTLP hot path. Wire with bounded work queue + circuit breaker.
- P-19: Mask `webhookUrl` in `GET /notification-channels` API response. Store webhook URL secrets separately from the channel config JSONB.

---

### Phase 3 — Export Refactor (1.5-2 weeks)

**What:** Real Parquet export (COMP-01) and S3 destination (COMP-02). Requires an `ExportFormatWriter` / `ExportDestinationStrategy` refactor of `ExportService.executeExport()`. The cross-tenant data leak at line 65 must be fixed in this phase (or preferably as a standalone hotfix before this phase begins).

**Rationale:** The ExportService refactor is the one moderate-risk change in the milestone (touches all export paths). Doing it after two stable phases keeps the codebase in a known-good state. The strategy pattern refactor must be atomic — extract existing JSON/CSV writers, add Parquet/S3 writers, then test all four paths together before merging.

**Delivers:** Proper columnar Parquet files readable by data tools. Automated daily S3 export for enterprise data pipeline integration.

**Features:** COMP-01, COMP-02

**Pitfalls to prevent:**
- P-7: Fix `ExportService.java:65` (`SELECT * FROM table` -> `SELECT * FROM table WHERE tenant_id = ?`) BEFORE this phase ships. Cross-tenant data leak is the highest-severity defect in the milestone.
- P-8: Any new ClickHouse-backed export path must pass `tenantId` to the store layer. Do not rely on the PostgreSQL run-level gate alone.
- P-11: Define a versioned write schema from day 1 (e.g., `SpanExportV1`). Only add nullable columns in future versions — never rename or remove.
- P-12: Store S3 credentials in a per-tenant `export_destinations` table, never in `ExportJob.destinationPath`.

---

### Phase 4 — Eval Intelligence (2-3 weeks)

**What:** Automated eval generation (EVAL-01) and CI/CD eval gate (COMP-05). EVAL-01 is the highest-complexity feature in the milestone — start the backend service early. COMP-05 is moderate (new `EvalGate` model + V9 migration + GitHub Action wrapper). Both compose existing services with minimal new coupling.

**Rationale:** Both features depend on stable `EvalService` (already stable). EVAL-01 backend is independent of the review UI and can build first. COMP-05 can build in parallel since it depends only on `EvalController` being stable.

**Delivers:** Automatic test case generation from production traces. CI regression detection for LLM quality.

**Features:** EVAL-01, COMP-05

**Pitfalls to prevent:**
- P-14: Human review gate is mandatory. The `status` field (GENERATED -> PENDING_REVIEW -> APPROVED) must be in the data model from day 1. No auto-approval path. No more than 20% of generated cases may enter dataset without human review.
- P-13: Run each eval case minimum 3 times; use median score, not single-pass. `temperature=0.0` does not guarantee determinism from cloud providers.
- P-16: CI eval gate must use separate exit codes: exit 1 = eval regression, exit 2 = infrastructure error. Retry up to 3 times with exponential backoff before exit 2.
- P-10: `EvalGateService` and `AutoEvalGenerationService` must capture `tenantId` at the synchronous entry point, not inside async lambdas.

---

### Phase 5 — Frontend Pages (2-3 weeks, parallel-capable with Phase 4)

**What:** Six new Next.js 14 app-router pages (SSO config, SCIM token management, eval gates, eval generation, export settings, nav wiring). Pages can begin as soon as their backing API phase stabilizes.

**Rationale:** Frontend has no backward-compatibility risk. SSO/SCIM pages can start after Phase 1. Export settings after Phase 3. Eval gate + generation pages after Phase 4. With a separate frontend team these overlap; with one team they are staggered.

**Delivers:** Complete UI surface for all enterprise features. Admin self-service configuration.

**Pitfalls to prevent:**
- P-9: Every new endpoint must perform an ownership assertion after `findById`. Establish a project rule: `result.tenantId().equals(TenantContext.getTenantId())` before returning any resource.
- P-17: Every new ClickHouse query must include `LIMIT` + `OFFSET`. Aggregate pages (RAG metrics, clustering) must aggregate at the ClickHouse layer with `GROUP BY`, never in Java.
- P-18: List endpoints must return all display fields in one response. No per-row `useEffect` fetches.

---

## Critical Watch-Outs

### 1. Cross-Tenant Data Leak at ExportService.java:65 — Fix Before Any Export Ships

`ExportService.java:65` executes `SELECT * FROM <table>` with no `WHERE tenant_id = ?` clause. Every export job reads all rows across all tenants. With S3 destination, this pushes other tenants' data to the requesting tenant's S3 bucket. This is a data breach, not a bug.

**Prevention:** `"SELECT * FROM " + table + " WHERE tenant_id = ?"` bound to `job.tenantId()`. Apply `queryFilter` as additional parameterized `AND` clauses. Add a cross-tenant integration test asserting zero row overlap between tenants. This test must pass before the export phase merges.

---

### 2. Spring Security 6.5.0 Pin Blocks All SSO Work

`chorus-observe-server/build.gradle.kts` pins three Security artifacts at 6.5.0. Boot 4.0.0 BOM provides Security 7.0.0. SAML2 and OAuth2 starters require Security 7 APIs absent in 6.5.0. The explicit pin wins in Gradle conflict resolution — there is no automatic fix. This must be resolved as a prerequisite story before any AUTH sprint.

**Prevention:** Remove all three explicit pins. Verify the existing JWT filter chain compiles and tests pass against Security 7.0.0. Scope as Phase 0 before Phase 1 planning locks.

---

### 3. SSO Users Bypass or Are Blocked by RBAC

`RbacAuthorizationFilter` reads permissions from `request.getAttribute("scopes")`, populated only by `JwtAuthFilter` and `ApiKeyAuthFilter`. SAML and OAuth2 paths never set this attribute. Result: SSO users either get 403 on every request or pass every permission check regardless of role (depending on the filter's null-handling behavior — it currently fails open).

**Prevention:** Write `SsoScopesAdapterFilter` positioned after Spring Security's SSO filters. It reads `SecurityContextHolder.getContext().getAuthentication().getAuthorities()`, maps them to the existing scope strings, and writes `request.setAttribute("scopes", mappedScopes)`. Additive only — no existing filter is modified.

---

### 4. SCIM Provisioning Creates Duplicate Users on Email Match or Case Mismatch

SCIM `POST /scim/v2/Users` succeeds in creating a new user row even when a local email/password user already exists, because the `ON CONFLICT` key is `user_id` (UUID), not email. Azure AD sends `User@Corp.Com`; local registration used `user@corp.com` — `findByEmail` at line 56 treats them as different users.

**Prevention:** (a) SCIM POST must call `findByEmail(tenantId, email)` before creating — if found, update the row to link the IdP external ID. (b) Lowercase-normalize email at every write path. (c) Replace `UNIQUE(tenant_id, email)` index with `CREATE UNIQUE INDEX ON users (tenant_id, LOWER(email))`. (d) Change lookup to `LOWER(email) = LOWER(?)`.

---

### 5. LLM Judge Non-Determinism Flaps the CI Eval Gate

`LlmJudgeScorer` runs a single evaluation pass at `temperature=0.0`. Cloud providers (OpenAI, Anthropic) do not guarantee deterministic output at temperature zero due to parallel inference sampling. A single-pass gate will produce false negative failures on unchanged code, eroding engineer trust in the gate.

**Prevention:** Run each eval case minimum 3 times; use median score. Configure pass threshold at p50 (2/3 runs pass). Cache results by `(prompt_hash, expected_hash, model_version)`. `ParallelEvalRunner.java` already parallelizes — add a `runs` parameter to make N-run scoring a first-class configuration option.

---

## Open Questions

| # | Question | Impact | Recommended Default |
|---|---------|--------|---------------------|
| Q1 | Does Phase 0 (Security 6.5->7 migration) break anything in the existing JWT chain that requires more than 1-3 days? | If yes, Phase 1 start date shifts | Scope a spike: remove pins, run tests, measure breakage before committing to phase dates |
| Q2 | Is ClickHouse `ch_spans` schema being modified to add `tenant_id` this milestone? | Required before Parquet export from ClickHouse and RAG metrics (UI-06) ship | Required — add to Phase 3 scope explicitly |
| Q3 | Should SCIM Groups be in scope for v1? | Adds ~0.5 weeks to AUTH-03 | Defer unless first enterprise customer requires group-to-role mapping |
| Q4 | Is the chorus-studio (Next.js) team on the same timeline as the backend? | If separate team, Phase 5 fully parallelizes with Phases 3-4 | Assume same team, staggered after API phases, unless confirmed |
| Q5 | AWS-only S3 or must MinIO be supported at GA? | MinIO works with AWS SDK v2 via custom endpoint — no separate code path | Document MinIO support via endpoint override; do not build a separate code path |
| Q6 | Is Parquet schema versioning a v1 requirement or v1.1? | Deferring creates migration pain when columns change | Include in v1 — cost is low, regret of not having it is high |
