# Requirements: Chorus Observe — Enterprise Feature Parity

**Defined:** 2026-05-23
**Milestone:** v1.0
**Core Value:** Give enterprise teams complete visibility into every LLM call, agent decision, and evaluation outcome — fully self-hosted, with no feature gates.

---

## v1.0 Requirements

### Phase 0 — Security Migration Prerequisite

- [ ] **SEC-01**: Dev removes explicit Spring Security 6.5.0 version pins from `chorus-observe-server/build.gradle.kts` so the Boot 4.0.0 BOM resolves Security 7.0.0
- [ ] **SEC-02**: All existing JWT/RBAC auth tests pass against Security 7.0.0 with no filter-chain regressions

### Authentication — OAuth2/OIDC SSO

- [ ] **AUTH-01**: User can log in via Google OIDC (SSO button on login page)
- [ ] **AUTH-02**: User can log in via GitHub OAuth2
- [ ] **AUTH-03**: User can log in via generic OIDC provider (Azure AD, Okta)
- [ ] **AUTH-04**: First-time SSO login auto-provisions a user with VIEWER role (JIT provisioning)
- [ ] **AUTH-05**: SSO login issues a Chorus JWT so the existing RBAC filter continues to work unchanged
- [ ] **AUTH-06**: Admin can configure OAuth2/OIDC IdP per tenant via settings page

### Authentication — SAML 2.0

- [ ] **SAML-01**: User can log in via SP-initiated SAML flow (Okta, Azure AD, ADFS)
- [ ] **SAML-02**: SAML assertion replay is blocked (assertion ID cache, 2-minute clock skew tolerance)
- [ ] **SAML-03**: Per-tenant SAML IdP config (entity ID, signing cert, ACS URL) is stored in DB and loaded at request time
- [ ] **SAML-04**: SAML login JIT-provisions a user on first successful assertion

### Authentication — SCIM v2

- [ ] **SCIM-01**: IdP can create a user via `POST /scim/v2/Users`
- [ ] **SCIM-02**: IdP can read, update, and deactivate users via `GET/PUT/PATCH/DELETE /scim/v2/Users/{id}`
- [ ] **SCIM-03**: SCIM filter queries work (`?filter=userName eq "..."`)
- [ ] **SCIM-04**: `GET /scim/v2/ServiceProviderConfig` returns supported capabilities
- [ ] **SCIM-05**: SCIM endpoints authenticate via a separate bearer token (not the user JWT)
- [ ] **SCIM-06**: SCIM `DELETE` soft-deactivates users — preserves audit trail, no hard delete
- [ ] **SCIM-07**: Duplicate user creation is prevented via find-before-create and `LOWER(email)` unique index

### Export

- [ ] **EXP-01**: Cross-tenant data leak at `ExportService.java:65` is fixed (`tenant_id` filter on all export queries)
- [ ] **EXP-02**: User can export spans as real Apache Parquet files (`carpet-record:0.7.1`, Hive-partitioned by date)
- [ ] **EXP-03**: Parquet files are readable by pandas, DuckDB, and Athena with correct column types
- [ ] **EXP-04**: Parquet schema version is embedded in file metadata for schema evolution support
- [ ] **EXP-05**: User can configure a MinIO (or any S3-compatible) endpoint as export destination (AWS SDK v2 custom endpoint)
- [ ] **EXP-06**: Export page in Chorus Studio lets user configure jobs, view job status, and download results

### Alerting

- [ ] **ALERT-01**: User can receive alert notifications in Microsoft Teams via Power Automate webhook
- [ ] **ALERT-02**: Teams webhook URL is masked in all API responses (never returned in plaintext)
- [ ] **ALERT-03**: Teams alerts use Adaptive Card JSON payload (no legacy O365 connector format)

### Evaluations — Hallucination Evaluator

- [ ] **EVAL-01**: `HallucinationScorer` is exposed as evaluator kind `"hallucination"` via `EvaluatorController`
- [ ] **EVAL-02**: Hallucination evaluator runs asynchronously post-ingestion (never on the OTLP hot path)

### Evaluations — Automated Eval Generation

- [ ] **EVAL-03**: User can trigger automated eval case generation from a set of production run IDs
- [ ] **EVAL-04**: Generated cases require human approval before entering a dataset (`GENERATED → PENDING_REVIEW → APPROVED` — no auto-approval path)
- [ ] **EVAL-05**: Generated case review UI shows source trace ID alongside the proposed input/expected output

### Evaluations — CI/CD Gate

- [ ] **EVAL-06**: CI/CD eval gate GitHub Action triggers an eval run against a dataset and fails the build on regression
- [ ] **EVAL-07**: Eval gate uses 3-run median scoring per case (prevents LLM non-determinism flap)
- [ ] **EVAL-08**: Eval gate exits with code 1 for eval regression, code 2 for infrastructure error, with retry on code 2

### Frontend Pages

- [ ] **UI-01**: `/prompts` page lists all prompt versions with tags, creation date, and A/B test status
- [ ] **UI-02**: `/playground` page lets user execute a prompt variant live, compare model outputs, and view cost estimate
- [ ] **UI-03**: `/insights` page shows conversation clusters with representative traces, topic labels, and counts
- [ ] **UI-04**: `/feedback` page shows annotation queue with score, skip, and comment workflow
- [ ] **UI-05**: `/export` page lets user configure export jobs, view job status, and download results
- [ ] **UI-06**: `/rag` page shows RAG retrieval metrics: context precision, recall, latency, and hit rate

---

## Future Requirements (deferred)

### Authentication
- SCIM Groups push for role mapping — defer until first enterprise customer requires group-to-role mapping
- Per-tenant SSO enforcement (block email/password for SSO-only tenants) — v1.1
- IdP-initiated SAML SSO — v1.1
- SAML attribute mapping (IdP claim → Chorus role) — v1.1

### Export
- S3 presigned URL for large file downloads (serves files via application tier in v1 — acceptable for export file sizes)
- STS AssumeRole cross-account for S3 — v1.1

### Eval Generation
- Coverage clustering (embedding diversity) for smarter sampling — v1.1
- Failure-first sampling (weight toward low-scoring spans) — v1.1

---

## Out of Scope

| Feature | Reason |
|---------|--------|
| AWS-native S3 (not MinIO) | User confirmed MinIO for self-hosted; AWS SDK v2 supports both via endpoint override — same code |
| SCIM LDAP sync | Complexity without clear demand; SCIM v2 covers the need |
| Native mobile app | Tailwind responsive web covers the use case; native adds complexity without proportional value |
| Multi-region failover | Self-hosted deployment — infra is user's responsibility |
| Keycloak integration | Spring Security SAML2/OAuth2 native handles this; no need for a wrapper |
| Legacy Teams O365 connectors | Retired May 2026 — not implementable |

---

## Traceability

*Updated by roadmapper — 2026-05-23*

| Requirement | Phase | Status |
|-------------|-------|--------|
| SEC-01, SEC-02 | Phase 1 — Security Foundation | Pending |
| AUTH-01 – AUTH-06 | Phase 2 — Enterprise Authentication | Pending |
| SAML-01 – SAML-04 | Phase 2 — Enterprise Authentication | Pending |
| SCIM-01 – SCIM-07 | Phase 2 — Enterprise Authentication | Pending |
| EXP-01 – EXP-06 | Phase 3 — Export Refactor | Pending |
| ALERT-01 – ALERT-03 | Phase 4 — Low-Risk Additive Features | Pending |
| EVAL-01 – EVAL-02 | Phase 4 — Low-Risk Additive Features | Pending |
| EVAL-03 – EVAL-05 | Phase 5 — Eval Intelligence | Pending |
| EVAL-06 – EVAL-08 | Phase 5 — Eval Intelligence | Pending |
| UI-01 – UI-06 | Phase 6 — Frontend Pages | Pending |

**Coverage:**
- v1.0 requirements: 42 total
- Mapped to phases: 42
- Unmapped: 0 ✓

---
*Requirements defined: 2026-05-23*
*Last updated: 2026-05-23 — roadmap created, coverage corrected to 42 requirements*
