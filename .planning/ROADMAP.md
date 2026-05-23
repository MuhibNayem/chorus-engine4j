# Roadmap: Chorus Observe — Enterprise Feature Parity

## Overview

Milestone v1.0 closes all actionable competitive gaps versus Langfuse, Braintrust, and Galileo. Six phases execute in strict dependency order: a security migration prerequisite unblocks enterprise authentication, which anchors user identity for export safety and eval workflows, while additive features and frontend pages complete the surface. Every one of the 42 v1.0 requirements maps to exactly one phase.

## Phases

**Phase Numbering:**
- Integer phases (1, 2, 3): Planned milestone work
- Decimal phases (2.1, 2.2): Urgent insertions (marked with INSERTED)

Decimal phases appear between their surrounding integers in numeric order.

- [x] **Phase 1: Security Foundation** - Remove Spring Security 6.5.0 pins so the Boot 4.0.0 BOM resolves Security 7.0.0; all auth tests pass
- [ ] **Phase 2: Enterprise Authentication** - OAuth2/OIDC SSO + SAML 2.0 + SCIM v2 provisioning sharing a single V7 Flyway migration
- [ ] **Phase 3: Export Refactor** - Fix cross-tenant data leak, implement real Parquet export, add MinIO/S3 destination
- [ ] **Phase 4: Low-Risk Additive Features** - Microsoft Teams dispatcher and hallucination evaluator (additive only, no existing flows touched)
- [ ] **Phase 5: Eval Intelligence** - Automated eval generation with human review gate and CI/CD eval gate
- [ ] **Phase 6: Frontend Pages** - Six new Next.js 14 app-router pages wiring all enterprise backend APIs

## Phase Details

### Phase 1: Security Foundation
**Goal**: Spring Security 7.0.0 is the active version on the classpath and all existing JWT/RBAC auth flows pass without regressions, unblocking all subsequent auth work.
**Depends on**: Nothing (first phase)
**Requirements**: SEC-01, SEC-02
**Success Criteria** (what must be TRUE):
  1. Running `./gradlew dependencies` shows `spring-security-core:7.0.x` (no 6.5.0 artifacts) for the `chorus-observe-server` module
  2. All existing JWT login, token refresh, and logout integration tests pass green against Security 7.0.0
  3. All existing RBAC method-security tests (`@PreAuthorize`, role-gated endpoints) pass without filter-chain regressions
  4. The build compiles cleanly with zero Security-version conflict warnings in the Gradle output
**Plans**: TBD

### Phase 2: Enterprise Authentication
**Goal**: Enterprise users can authenticate via OAuth2/OIDC SSO or SAML 2.0, and identity providers can provision and manage users through the SCIM v2 API, all sharing a single Flyway migration.
**Depends on**: Phase 1
**Requirements**: AUTH-01, AUTH-02, AUTH-03, AUTH-04, AUTH-05, AUTH-06, SAML-01, SAML-02, SAML-03, SAML-04, SCIM-01, SCIM-02, SCIM-03, SCIM-04, SCIM-05, SCIM-06, SCIM-07
**Success Criteria** (what must be TRUE):
  1. User can click "Sign in with Google" or "Sign in with GitHub" on the login page and reach the Chorus dashboard (JIT-provisioned with VIEWER role on first login, existing RBAC filter unmodified)
  2. User can complete an SP-initiated SAML login flow against a real Okta or Azure AD test tenant, including replay-attack rejection on assertion reuse within 2 minutes
  3. An identity provider can POST to `/scim/v2/Users` with a bearer token, and a subsequent GET returns the created user; a SCIM DELETE soft-deactivates the user (row preserved with `active=false`)
  4. An admin can configure an OAuth2/OIDC IdP per tenant through the settings page, and subsequent logins use that configuration without server restart
  5. Attempting to create a SCIM user with the same email as an existing user (including case variants) returns a 409 Conflict, not a duplicate row
**Plans**: TBD
**UI hint**: yes

### Phase 3: Export Refactor
**Goal**: Export jobs are tenant-isolated, produce real Apache Parquet files readable by pandas/DuckDB/Athena, and can deliver output to any MinIO or S3-compatible destination.
**Depends on**: Phase 1
**Requirements**: EXP-01, EXP-02, EXP-03, EXP-04, EXP-05, EXP-06
**Success Criteria** (what must be TRUE):
  1. An export job for Tenant A returns zero rows belonging to Tenant B (verified by a cross-tenant integration test)
  2. A completed export job produces a `.parquet` file that `pandas.read_parquet()`, `duckdb.read_parquet()`, and Athena can open without schema errors
  3. The Parquet file metadata contains a `schema_version` key (e.g., `SpanExportV1`) enabling schema evolution tracking
  4. User can configure a MinIO endpoint URL, bucket, and credentials in the Export settings page, and a test export job delivers files to that bucket
  5. The Export page in Chorus Studio shows job history, current job status, and a download link for completed local exports
**Plans**: TBD
**UI hint**: yes

### Phase 4: Low-Risk Additive Features
**Goal**: Alert notifications reach Microsoft Teams channels via Power Automate webhooks, and hallucination detection is available as a first-class evaluator kind in the existing evaluation framework.
**Depends on**: Phase 1
**Requirements**: ALERT-01, ALERT-02, ALERT-03, EVAL-01, EVAL-02
**Success Criteria** (what must be TRUE):
  1. An alert rule with a Teams channel configured fires an Adaptive Card message to the configured Power Automate webhook URL when the threshold is crossed
  2. A `GET /notification-channels` response for a Teams channel shows a masked webhook URL (e.g., `***...last4chars`), never the plaintext secret
  3. Creating an evaluator with `kind="hallucination"` and running it against a span returns a score between 0 and 1 within the evaluator results UI
  4. A hallucination evaluator never fires synchronously on the OTLP ingestion path (span ingest latency is unaffected)
**Plans**: TBD

### Phase 5: Eval Intelligence
**Goal**: Users can generate evaluation cases automatically from production traces with a mandatory human review gate, and CI/CD pipelines can fail builds on eval score regressions using a GitHub Action with stable N-run scoring.
**Depends on**: Phase 2, Phase 4
**Requirements**: EVAL-03, EVAL-04, EVAL-05, EVAL-06, EVAL-07, EVAL-08
**Success Criteria** (what must be TRUE):
  1. User can select a set of production run IDs and trigger eval case generation; generated cases appear in a review queue with status `PENDING_REVIEW` and a visible source trace ID
  2. A generated case cannot reach a dataset without an explicit human approval action; there is no code path that sets status directly from `GENERATED` to `APPROVED` without touching `PENDING_REVIEW`
  3. A GitHub Action configured with the Chorus eval gate YAML fails with exit code 1 when an eval score drops below the configured threshold, and exits with code 2 (not 1) when the Chorus API is unreachable
  4. Each eval case is scored a minimum of 3 times; the reported score is the median of those runs, preventing single-run LLM non-determinism from causing false failures
**Plans**: TBD
**UI hint**: yes

### Phase 6: Frontend Pages
**Goal**: All six missing Chorus Studio pages are live, giving users a complete UI surface for prompt management, playground execution, clustering insights, annotation queue, export control, and RAG metrics.
**Depends on**: Phase 2, Phase 3, Phase 5
**Requirements**: UI-01, UI-02, UI-03, UI-04, UI-05, UI-06
**Success Criteria** (what must be TRUE):
  1. `/prompts` page lists all prompt versions with tags, creation date, and A/B test status; user can create a new version from this page
  2. `/playground` page lets user select a prompt variant, execute it live against a configured model, compare outputs of two variants side by side, and view a cost estimate for each run
  3. `/insights` page displays conversation clusters with topic labels, representative trace links, and cluster member counts
  4. `/feedback` page shows the annotation queue; user can submit a score, skip an item, or add a comment, and the item moves out of the queue after action
  5. `/export` page lets user configure export jobs and view job status (mirrors EXP-06 backend, now fully wired in the UI)
  6. `/rag` page shows retrieval metrics — context precision, recall, latency distribution, and hit rate — with data sourced from ClickHouse aggregations (not per-row Java loops)
**Plans**: TBD
**UI hint**: yes

## Progress

**Execution Order:**
Phases execute in dependency order: 1 → 2 → 3 → 4 → 5 → 6
(Phases 2, 3, 4 may proceed in parallel after Phase 1; Phase 5 requires Phases 2 and 4; Phase 6 requires Phases 2, 3, and 5.)

| Phase | Plans Complete | Status | Completed |
|-------|----------------|--------|-----------|
| 1. Security Foundation | 4/4 | ✓ Complete | 2026-05-23 |
| 2. Enterprise Authentication | 5/5 | Planned | — |
| 3. Export Refactor | 0/TBD | Not started | - |
| 4. Low-Risk Additive Features | 0/TBD | Not started | - |
| 5. Eval Intelligence | 0/TBD | Not started | - |
| 6. Frontend Pages | 0/TBD | Not started | - |

---
*Roadmap created: 2026-05-23 — Milestone v1.0 Enterprise Feature Parity*
*42 requirements mapped across 6 phases — coverage: 100%*
