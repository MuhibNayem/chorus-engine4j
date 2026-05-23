# Domain Pitfalls: Enterprise Feature Addition to Chorus Observe

**Domain:** LLM observability platform — adding enterprise auth, export, evals, and alerting
**Researched:** 2026-05-23
**Overall confidence:** HIGH (grounded in actual codebase files + Spring Security 6.5 official docs)

---

## Auth & Security

### P-1: SAML2/OAuth2 Authentication Does Not Flow Into Existing RBAC — Silent Auth Bypass
**Severity:** High
**Phase:** AUTH-01 (OAuth2 SSO), AUTH-02 (SAML 2.0)

**What goes wrong:** Spring Security's `Saml2WebSsoAuthenticationFilter` stores the authenticated principal in `SecurityContextHolder` after successful SAML login. The existing `RbacAuthorizationFilter` (in `security/RbacAuthorizationFilter.java`) reads permissions from `request.getAttribute("scopes")` — populated by `JwtAuthFilter` and `ApiKeyAuthFilter` at lines 56-57 and 73-74 respectively. SAML/OAuth2 paths never set `request.getAttribute("scopes")`. Result: every SSO-authenticated user gets an empty scope set and is blocked by any protected endpoint — or if the filter fails-open on null scopes (it does fail-open when `required` header is null, line 40-41), they get full access regardless of role.

**Confirmed in official docs:** "If SAML 2.0 authentication is successful, the resulting `Authentication` object is set on the `SecurityContextHolder`, and the `Saml2WebSsoAuthenticationFilter` allows the request to proceed through the rest of the application's filter chain." (Spring Security 6.5 reference, SAML2 Login Overview)

**Why it happens:** The existing security chain predates Spring Security's standard `SecurityContextHolder` model — it uses request attributes as a side-channel for scopes, which SAML/OAuth2 providers never know about.

**Prevention:** Write a post-SSO adapter filter positioned after `Saml2WebSsoAuthenticationFilter` that reads `SecurityContextHolder.getContext().getAuthentication().getAuthorities()`, maps them to the existing scope strings, and writes `request.setAttribute("scopes", mappedScopes)`. This preserves the existing `RbacAuthorizationFilter` without rewriting it. Alternatively, refactor `RbacAuthorizationFilter` to read from `SecurityContextHolder` directly — but that requires touching every existing auth path. The adapter pattern is lower risk for this milestone.

**Detection:** Write an integration test: authenticate via SAML mock, then call a permission-gated endpoint. Verify the response is not 403 for a user with the required role and not 200 for a user without it.

---

### P-2: Existing JWT Sessions Collide With SCIM-Provisioned Users on Email Match
**Severity:** High
**Phase:** AUTH-01 / AUTH-03 (SCIM)

**What goes wrong:** When SSO is enabled and SCIM provisions a user from the IdP, the SCIM provisioner creates a new `user_id` (UUID) for that user. If the same person previously registered with local email/password, they already have a different `user_id` in the `users` table. Result: two rows for the same human, two divergent RBAC assignment histories, and all existing JWTs for the old `user_id` remain valid but point to a ghost identity.

**`UserRepository.java` evidence:** `findByEmail` (line 56) correctly scopes by `tenant_id` + `email`, so the second insert would succeed if the SCIM provisioner uses a new UUID and the `ON CONFLICT` key is `user_id` (which it is, line 31).

**Prevention:**
1. SCIM `Users` POST endpoint must first call `userRepository.findByEmail(tenantId, email)` before creating. If found, update the existing row (link the IdP external ID) rather than insert.
2. Add a `external_idp_id` column to `users` to store the IdP subject claim.
3. Invalidate all existing JWTs for users migrated to SSO at cutover by rotating the per-user JWT signing secret or recording a `password_changed_at` sentinel that the JWT validator checks.
4. Add a `UNIQUE` constraint on `(tenant_id, LOWER(email))` — not just `email` — to make duplicates impossible at the database level.

**Detection:** Test the SCIM POST `/scim/v2/Users` endpoint with the email of an existing local user. Assert the response is 200 (update) not 201 (create).

---

### P-3: SCIM Email Case Sensitivity Creates Duplicate Users
**Severity:** High
**Phase:** AUTH-03 (SCIM provisioning)

**What goes wrong:** IdPs (Okta, Azure AD) normalize email addresses differently. Azure AD sends `User@Corp.Com`; local registration used `user@corp.com`. `UserRepository.findByEmail` uses `WHERE tenant_id = ? AND email = ?` (line 56) — an exact-case match. These are treated as two different users.

**Prevention:**
- Lowercase-normalize email at every write path: in `AuthenticationService`, `UserService`, and the future `ScimUserController`.
- Change the `users` table unique index from `UNIQUE(tenant_id, email)` to a functional index: `CREATE UNIQUE INDEX users_tenant_email_ci ON users (tenant_id, LOWER(email))`.
- Change the lookup query to `WHERE tenant_id = ? AND LOWER(email) = LOWER(?)`.

**Detection:** Integration test: create user with `Foo@Example.COM` via SCIM, then try to log in as `foo@example.com` via JWT. Should map to the same user_id.

---

### P-4: SAML Assertion Replay and Clock Skew
**Severity:** High
**Phase:** AUTH-02 (SAML 2.0)

**What goes wrong:** SAML assertions carry a `NotBefore` / `NotOnOrAfter` validity window. If the self-hosted server clock drifts more than 2 minutes from the IdP, every assertion fails. Conversely, without replay protection (caching the `AssertionID` after first use), an intercepted assertion can be reused within its validity window.

**Prevention:**
- Use `spring-security-saml2-service-provider` (Spring Security 6.x native, not the deprecated `spring-security-saml2`). It enforces `NotOnOrAfter` by default but allows configuring a clock skew via `OpenSamlAuthenticationProvider.setResponseTimeValidationSkew(Duration.ofMinutes(2))`.
- Enable `InResponseTo` validation (the `AuthnRequest` ID must match the assertion's `InResponseTo`). Spring Security 6.5 SAML2 login does this by default.
- Implement an assertion ID cache (Redis or in-memory with TTL matching `NotOnOrAfter`) to reject replayed assertions.
- Run NTP on the self-hosted server and document it as an installation requirement.

**Detection:** Test with a deliberately skewed clock (server time + 3 minutes ahead of IdP). The login should fail with a clock-skew error, not succeed.

---

### P-5: `PUBLIC_PATHS` Duplication — SCIM Endpoints Can Bypass or Block Auth
**Severity:** Medium
**Phase:** AUTH-03 (SCIM)

**What goes wrong:** `JwtAuthFilter.java` (line 30-35) and `ApiKeyAuthFilter.java` (line 30-35) each maintain their own `PUBLIC_PATHS` set as a private constant. SCIM endpoints (`/scim/v2/Users`, `/scim/v2/Groups`) use Bearer token auth (IdP-issued service account token, not user JWTs). If SCIM paths are not in `PUBLIC_PATHS`, the `JwtAuthFilter` attempts JWT validation on every SCIM call and either rejects them (401) or calls `TenantContext.clear()` in the `finally` block before the SCIM handler runs. If SCIM paths are incorrectly added to `PUBLIC_PATHS`, they become unauthenticated.

**Prevention:**
- Centralize path lists into a shared `SecurityPathConfig` bean that both filters read.
- Add SCIM paths to a new `SCIM_PATHS` matcher, not `PUBLIC_PATHS`. SCIM needs its own auth mechanism: validate the Bearer token against a stored SCIM service account credential, then call `TenantContext.set()` with the provisioner's tenant before delegating.
- Write a dedicated `ScimAuthFilter` for the `/scim/v2/**` path prefix.

---

### P-6: Raw API Key Storage Pattern
**Severity:** Medium
**Phase:** AUTH-03 (SCIM) / COMP-02 (S3 export)

**What goes wrong:** `ApiKeyAuthFilter.java` comment (line 89-93) explicitly states the lookup is by raw key value (not bcrypt): `return apiKeyRepository.findByKeyHash(rawKey)` where `rawKey` is the literal key string. This means API keys are stored in plaintext (the "hash" column holds the raw key). SCIM service account tokens and S3 credential references will likely reuse this same pattern.

**Prevention:**
- Before adding SCIM and S3 credentials to the `api_keys` table (or a sibling table), resolve the storage model: store a fast hash (SHA-256 truncated) for lookup + a separate column for HMAC verification, or accept the plaintext model and document the security posture explicitly.
- At minimum, add a `SCIM` scope type to `api_keys` so SCIM tokens cannot be accidentally used as API ingestion keys.

---

## Data Integrity

### P-7: ExportService Omits Tenant Filter — Cross-Tenant Data Leak Into S3
**Severity:** High (Critical before COMP-02 ships)
**Phase:** COMP-01 (Parquet), COMP-02 (S3 export)

**What goes wrong:** `ExportService.java` line 65:
```java
List<Map<String, Object>> rows = jdbc.queryForList("SELECT * FROM " + table);
```
There is no `WHERE tenant_id = ?` clause. Every export job, regardless of which tenant submitted it, reads every row in the target table across all tenants. With the current `LOCAL` destination, this produces a cross-tenant file on the server filesystem. When COMP-02 adds S3 destination, the same query pushes every tenant's data to the requesting tenant's S3 bucket.

Additionally, `queryFilter` (the field accepted at submission) is stored in the job record but is never applied to the SQL (line 65 ignores it entirely).

**Fix before this milestone ships:**
```java
// Replace line 65 with:
String sql = "SELECT * FROM " + table + " WHERE tenant_id = ?";
List<Map<String, Object>> rows = jdbc.queryForList(sql, job.tenantId());
```
Also apply `queryFilter` fields as additional `AND` clauses with parameterized values.

**Detection:** Submit export jobs from two different tenant accounts. Assert that tenant A's export file contains zero rows owned by tenant B.

---

### P-8: ClickHouse Span Queries Filter Only by run_id — No Tenant Guard at Storage Layer
**Severity:** High
**Phase:** COMP-01 (Parquet export from ClickHouse), UI-06 (RAG metrics)

**What goes wrong:** `ClickHouseSpanStore.findSpansByRunId` (line 137-152) queries `WHERE run_id = ?` with no `tenant_id` filter. The ClickHouse tables (`ch_spans`, `ch_llm_calls`, `ch_tool_calls`) do not appear to include a `tenant_id` column in the INSERT schema (line 52-55 of `ClickHouseSpanStore.java`). The current multi-tenant safety relies on the upstream PostgreSQL `runs` table gating access — the caller verifies the run belongs to the tenant before calling ClickHouse. New endpoints added for export or RAG metrics that call the ClickHouse store directly without this upstream check create a data leak path.

**Prevention:**
- Add `tenant_id` column to `ch_spans`, `ch_llm_calls`, `ch_tool_calls` ClickHouse tables in a migration.
- Add `tenant_id` to all INSERT statements in `ClickHouseSpanStore`.
- Change all query methods to include `AND tenant_id = ?`.
- Any new endpoint that queries ClickHouse directly must pass tenantId from `TenantContext.getTenantId()` down to the store layer — do not rely on the run-level gate alone.

---

### P-9: findById Queries Lack Tenant Scoping — IDOR Exposure for New Endpoints
**Severity:** High
**Phase:** All UI phases (UI-01 through UI-06), COMP-01/02

**What goes wrong:** `DashboardRepository.findById` (line 63) queries `WHERE dashboard_id = ?` with no tenant check. `AuditLogRepository.findById` (line 50) is the same pattern. If a new endpoint wires directly to `findById` and returns the result without verifying `result.tenantId().equals(TenantContext.getTenantId())`, a tenant who guesses a dashboard_id from another tenant gets the other tenant's data — Insecure Direct Object Reference (IDOR).

**Prevention:** Establish a project-wide rule: `findById` is safe only when followed by an ownership assertion:
```java
Dashboard dash = dashboardRepository.findById(id)
    .filter(d -> d.tenantId().equals(TenantContext.getTenantId()))
    .orElseThrow(() -> new ResourceNotFoundException("dashboard", id));
```
Or add `findByIdAndTenantId(String id, String tenantId)` variants to repositories that replace `findById` in controller paths.

Apply this check to every new endpoint in all 6 UI pages before each goes to code review.

---

### P-10: TenantContext Lost in Async Export and Eval Tasks — IllegalStateException
**Severity:** High
**Phase:** COMP-01 (Parquet), EVAL-01 (automated eval generation), COMP-03 (Teams), COMP-05 (CI gate)

**What goes wrong:** `TenantContext` uses a `ThreadLocal<Context>` (line 8 of `TenantContext.java`). The `JwtAuthFilter.doFilterInternal` calls `TenantContext.clear()` in the `finally` block (line 65) before the HTTP response completes — which runs before any `CompletableFuture` spawned in the handler finishes. Any async task (including `ExportService`'s `CompletableFuture.runAsync` at line 49) that calls `TenantContext.getTenantId()` inside the lambda will get `IllegalStateException: No tenant context available`.

`ExportService` correctly avoids this by passing `tenantId` as a method parameter (line 41). New features must follow the same discipline.

**Prevention:**
- Rule: never call `TenantContext.getTenantId()` inside a `CompletableFuture`, `@Async` method, virtual thread task, or any code that executes after filter teardown.
- Capture at submission: `String tenantId = TenantContext.getTenantId();` at the synchronous entry point, pass explicitly to the async worker.
- For Teams dispatcher and CI gate runner: pass tenantId as a constructor param to the task object, not as a field read at execution time.
- For eval generation worker: the service method that submits the job must extract tenantId before the `runAsync` call.

**Detection:** Write a test that submits an export job and then reads `TenantContext` inside the async lambda. It should fail to compile review or throw in testing, confirming the lambda doesn't inherit the context.

---

### P-11: Parquet Schema Evolution Breaks Existing Readers on Column Rename or Type Change
**Severity:** Medium
**Phase:** COMP-01 (Parquet export)

**What goes wrong:** The current `ExportService` writes JSON with a `.parquet` extension (line 110-113, the `PARQUET` branch is a stub). When COMP-01 replaces this with real Apache Parquet, the schema is derived from the current ClickHouse column set. If a future schema change renames a column (`run_id` to `trace_id`) or changes a type (String to UUID), existing Parquet files in S3 become unreadable by tools that infer schema from a file sample.

**Confirmed from `parquet-java` docs:** `ProtoParquetReader` accepts `ignoreUnknownFields=true` to handle evolution, but this only helps readers — writers must define a stable write schema separately from the table schema.

**Prevention:**
- Define a versioned Avro schema (`SpanExportV1.avsc`, `LlmCallExportV1.avsc`) used as the Parquet write schema. Pin this in a separate schema registry directory.
- Only add nullable columns to future schema versions (never remove or rename).
- Write the schema version into each Parquet file's metadata (`parquet.avro.read.schema` conf key).
- Store exported Parquet files in S3 with path prefix `/exports/schema-v1/tenant-{id}/` so version-specific partitions can be queried independently.

---

### P-12: S3 Credentials Leaked via Job Destination Path Stored in Database
**Severity:** Medium
**Phase:** COMP-02 (S3 export)

**What goes wrong:** `ExportJob` stores `destinationPath` in the database (the `destination_path` column). If S3 destination config (bucket, key prefix, credentials) is accepted as job submission parameters and stored in `destination_path` or `queryFilter` JSON, AWS credentials or pre-signed URL tokens end up in the `export_jobs` table — readable by any user in that tenant who can list jobs.

**Prevention:**
- Store S3 config (bucket, prefix, IAM role) in a tenant-level `export_destinations` table, not per-job.
- The job record stores only a reference ID to the destination config, not the credentials themselves.
- Use IAM role assumption (instance profile or IRSA) rather than static AWS credentials. If static credentials are required for self-hosted, encrypt them at rest and never return them in API responses.
- The `findByTenant` query on `export_jobs` must redact `destinationPath` in API responses — replace with a stable destination alias from the `export_destinations` table.

---

## Eval Reliability

### P-13: LLM Judge Non-Determinism Causes CI Gate to Flap on the Same Code
**Severity:** High
**Phase:** COMP-05 (CI/CD eval gate)

**What goes wrong:** `LlmJudgeScorer` (line 48) calls the LLM at `temperature=0.0`, but cloud LLM providers (OpenAI, Anthropic) do not guarantee deterministic output even at temperature zero due to parallel inference sampling. A CI gate that runs a single eval pass and fails the build if any score falls below a threshold will produce false negative failures on unchanged code — blocking engineers and eroding trust in the gate.

**Prevention:**
- Run each eval case N times (minimum 3, recommended 5) and use the median score, not a single sample. `ParallelEvalRunner.java` already parallelizes runs — add a `runs` parameter to the runner.
- Set the pass threshold at p50 (median pass), not p100 (all-pass). A threshold requiring all 5 runs to pass is too brittle; requiring 3/5 is more stable.
- Cache eval results by `(prompt_hash, expected_hash, model_version)` and only re-run when those change — avoids LLM cost on unchanged cases.
- The CI gate action should distinguish between "eval score regression" (hard fail) and "eval score variance increase" (soft warning).

---

### P-14: Automated Eval Generation Produces Test Cases That Are False Positives in Production
**Severity:** High
**Phase:** EVAL-01 (automated eval generation)

**What goes wrong:** Using an LLM to generate eval cases from production traces risks creating cases where the LLM generates an "expected output" that is itself hallucinated, confidently wrong, or matches the production model's biases exactly. These cases will always pass the LLM judge (same model evaluating outputs it generated the expected for), producing a test suite that measures nothing.

**Prevention:**
- Generate eval cases with a different model than the one used for LLM-as-judge scoring. If GPT-4o generates the expected outputs, use Claude as the judge.
- Require human review before a generated eval case becomes part of the persistent eval dataset. Add a `status` field to eval cases: `GENERATED → PENDING_REVIEW → APPROVED`. Only `APPROVED` cases run in CI.
- Track which production trace each generated case came from. If the source trace has a human feedback score (thumbs down, annotation), use that to seed the expected output instead of LLM generation.
- Set a maximum auto-approval rate — never auto-approve more than 20% of generated cases without human review.

---

### P-15: Hallucination Evaluator Adds LLM Call Latency on the Ingestion Hot Path
**Severity:** Medium
**Phase:** COMP-04 (hallucination evaluator)

**What goes wrong:** If the hallucination evaluator runs synchronously during span ingestion (as part of the `TieredGuardrailEngine` or inline in the OTLP receiver), it introduces 300-2000ms of LLM latency to every ingested span. The OTLP receiver is a high-throughput path.

**Prevention:**
- Wire the hallucination evaluator as an async post-ingestion scorer, not a synchronous guardrail. The guardrail path is for real-time blocking; hallucination detection is a forensic signal.
- Enqueue spans for hallucination scoring in a bounded work queue after acknowledgment is sent to the OTLP client.
- Add a circuit breaker: if the hallucination evaluator's LLM endpoint is slow or erroring, skip scoring and mark spans as `hallucination_score=null` rather than blocking ingestion.

---

### P-16: CI Eval Gate Treats Flaky Network Failures as Eval Failures
**Severity:** Medium
**Phase:** COMP-05 (CI/CD eval gate)

**What goes wrong:** The GitHub Action eval gate calls the Chorus Observe API to run evals and waits for results. If the API is temporarily unreachable (network timeout, deploy in progress), the action returns non-zero exit code — and the CI system interprets this as an eval regression, blocking the merge.

**Prevention:**
- Separate exit codes: exit 1 = eval regression (real failure), exit 2 = infrastructure error (skip/retry).
- The action should retry up to 3 times with exponential backoff before escalating to exit 2.
- CI pipelines should be configured to treat exit 2 as a soft failure (warning, not blocking).
- Document the distinction in the GitHub Action's README.

---

## Frontend Performance

### P-17: New UI Pages Load Unpaginated Data From ClickHouse — Full Table Scans
**Severity:** High
**Phase:** UI-01 through UI-06

**What goes wrong:** ClickHouse queries without a `LIMIT` clause on large trace tables (millions of rows) return all matching rows to the Java service, which then holds them in a `List<Map<String,Object>>` in heap before serializing to JSON. The RAG metrics page (UI-06) and Clustering Insights page (UI-03) are especially risky — they aggregate across potentially large corpora.

**Prevention:**
- Mandate a `LIMIT` and `OFFSET` on every new ClickHouse query that returns multiple rows. No endpoint returns more than 500 rows without cursor-based pagination.
- For aggregate pages (RAG metrics, clustering insights), compute aggregates at the ClickHouse layer with `GROUP BY` + materialized views, never in Java.
- Add a query timeout at the JDBC level: `clickhouseDataSource.setSocketTimeout(30_000)` to prevent hung requests.
- Run `EXPLAIN` on every new ClickHouse query in the PR description before merge.

---

### P-18: React Pages Trigger N+1 API Calls for Lists — Span Waterfall Repeated Per Row
**Severity:** Medium
**Phase:** UI-01, UI-03, UI-04, UI-06

**What goes wrong:** The existing Next.js frontend pattern for the runs list fetches each run's span summary in a separate `useEffect` per row (N+1 pattern). New pages that display lists with per-row detail (Feedback Queue UI-04, Clustering Insights UI-03) will inherit this pattern if not caught at review.

**Prevention:**
- New list endpoints must return all display fields for the list view in a single response — no per-row detail fetch.
- Use React Query's `useQueries` with batching rather than individual `useEffect` per row.
- Add a lint rule or PR checklist item: "Does this page fetch data inside a `.map()` or per-row render?"

---

### P-19: Teams Alert Dispatcher Exposes Webhook URL in Notification Channel Config API
**Severity:** Medium
**Phase:** COMP-03 (Microsoft Teams dispatcher)

**What goes wrong:** `NotificationChannel.config` (a `Map<String,Object>`) stores the Teams incoming webhook URL. `NotificationChannelRepository` serializes this to JSON in the database. If the `GET /notification-channels` endpoint returns the full `config` map, the webhook URL (which is effectively a secret — anyone with it can post to the Teams channel) is exposed to every user in the tenant with read access.

**Prevention:**
- Mask webhook URLs in API responses: return `{"webhookUrl": "https://outlook.office.com/webhook/***REDACTED***"}`.
- Store webhook URLs in a separate `notification_secrets` table with restricted DB permissions, referencing only a secret_id in `notification_channel.config`.
- Log the channel_id and channel_type, never the webhook URL, in application logs.

---

## Phase-Specific Warnings

| Phase Topic | Most Likely Pitfall | Required Mitigation |
|-------------|---------------------|---------------------|
| AUTH-01 OAuth2 SSO | P-1: RBAC scope not populated from SecurityContextHolder | Write adapter filter before wiring OAuth2 login |
| AUTH-02 SAML 2.0 | P-4: Assertion replay + P-1: RBAC bypass | Assertion ID cache + adapter filter |
| AUTH-03 SCIM | P-2: Duplicate users on email match + P-3: case mismatch + P-5: filter path gaps | Email normalization + findByEmail-before-create + centralized path config |
| COMP-01 Parquet | P-7: Cross-tenant export query (must fix first) + P-11: schema evolution | Fix ExportService line 65 before any export ships |
| COMP-02 S3 export | P-7 (worsens to remote leak) + P-12: credential storage | Tenant-level destination config, no credentials in job rows |
| COMP-03 Teams | P-19: webhook URL exposure + P-10: TenantContext in async | Mask config in responses, pass tenantId explicitly |
| COMP-04 Hallucination | P-15: latency on hot path | Async post-ingestion scorer only |
| COMP-05 CI gate | P-13: LLM judge flap + P-16: network errors → regression | N-run median + separate exit codes |
| EVAL-01 Eval generation | P-14: hallucinated test cases become false positives | Human review gate on generated cases |
| UI-01 through UI-06 | P-9: IDOR on findById + P-17: unpaginated ClickHouse | Ownership assertion after every findById + LIMIT on every query |

---

## Sources

- Spring Security 6.5 Reference — SAML2 Login: https://docs.spring.io/spring-security/reference/6.5/servlet/saml2/login/overview.html (HIGH confidence)
- Spring Security 6.5 Reference — SecurityContextHolder architecture: https://docs.spring.io/spring-security/reference/6.5/servlet/authentication/architecture.html (HIGH confidence)
- Apache Parquet Java — AvroParquetReader schema evolution: https://context7.com/apache/parquet-java/llms.txt (HIGH confidence)
- Codebase — `ExportService.java` line 65: cross-tenant SELECT without WHERE tenant_id (verified directly)
- Codebase — `RbacAuthorizationFilter.java` lines 40-55: scopes read from request attribute, not SecurityContextHolder (verified directly)
- Codebase — `TenantContext.java`: ThreadLocal implementation, cleared in JwtAuthFilter finally block (verified directly)
- Codebase — `UserRepository.findByEmail` line 56: case-sensitive exact match (verified directly)
- Codebase — `DashboardRepository.findById` line 63: no tenant_id filter (verified directly)
- Codebase — `LlmJudgeScorer.java` line 47: temperature=0.0 single-pass scoring (verified directly)
- Codebase — `ApiKeyAuthFilter.java` lines 89-93: raw key storage admission (verified directly)
