# Feature Landscape: Enterprise Feature Parity

**Domain:** LLM Observability Platform — Enterprise Feature Parity milestone
**Researched:** 2026-05-23
**Mode:** Features research for subsequent milestone (existing platform context)
**Overall confidence:** HIGH (existing code grounded findings; market patterns verified)

---

## How to Read This File

Each feature section follows this structure:

- **Table Stakes** — behaviors users expect; missing = product feels incomplete against Langfuse/Braintrust
- **Differentiators** — behaviors that would make Chorus Observe better than market leaders in this area
- **Complexity** — realistic estimate for a Java/Spring Boot + Next.js 14 team
- **Dependencies on Existing Code** — concrete classes and models already in the codebase
- **Anti-Features** — what NOT to build in this milestone
- **Build Order Note** — ordering constraint for the roadmapper

---

## Feature 1: Automated Eval Generation (EVAL-01)

**What "done" looks like:** An operator selects a date range and a minimum sample size, clicks "Generate Test Cases", and the system samples production traces (weighted toward low-scoring or diverse inputs), uses an LLM judge to synthesize expected outputs, and proposes N candidate test cases. The operator reviews each proposed case (approve / edit / reject), and approved cases land directly in an existing dataset.

### Table Stakes

| Behavior | Source of truth |
|----------|-----------------|
| Sample traces by recency + score diversity (not just random) | Braintrust pattern: online failures → eval cases |
| LLM-synthesize expected output from (input, actual output) pair | Standard LLM-as-judge technique |
| Human review gate before cases land in dataset | Required; synthetic ground truth needs validation |
| Show the source trace ID alongside the proposed test case | Traceability requirement |
| Cases appear in existing dataset after approval | Must integrate with DatasetController / DatasetService |

### Differentiators

- **Coverage clustering**: cluster proposed cases by embedding similarity before surfacing them, so the reviewer sees diverse samples rather than 20 paraphrases of the same question.
- **Auto-label by evaluator**: run the existing `AgentInvokerJudgeScorer` over proposed cases before review, surfacing which ones the judge is unsure about — those are the most valuable for the dataset.
- **Failure-first sampling**: weight sampling toward spans where existing evaluators already fired a low score, directly closing the incident → regression-test loop.

### Complexity

**HIGH.** Three distinct sub-problems: (1) trace sampling with diversity scoring requires embedding infrastructure; (2) LLM synthesis is straightforward but prompt engineering for quality expected outputs is non-trivial; (3) review UI is a new approval workflow. None of these are blockers individually, but they compose into 3-4 weeks of work.

### Dependencies on Existing Code

- `EvalService` + `EvalController` — generated cases must POST to the existing dataset ingest path
- `DatasetController` / `DatasetService` — receives approved test cases as dataset items
- `AgentInvokerJudgeScorer` — re-use as the LLM-synthesis backbone and for pre-scoring proposed cases
- `EmbeddingClusterer` — use for diversity sampling when selecting candidate traces
- `TraceClusteringEngine` — optional: use cluster labels to stratify sampling across topics
- `RunController` / `SpanController` — source of candidate trace data

### Anti-Features

- **Do not auto-approve.** Every synthetic case must pass human review before entering a dataset. Shipping without a gate produces noisy datasets and erodes evaluator trust.
- **Do not build a standalone eval-gen dataset.** Approved cases must land in the existing dataset model — no second dataset type.
- **Do not generate expected outputs deterministically** (e.g., copy the model's actual output as ground truth). That defeats the purpose.

### Build Order Note

Depends on DatasetService being solid (already validated). Can start in parallel with UI pages; the backend service is independent. Build the backend worker first, then the review UI.

---

## Feature 2: SSO / OAuth2 + SAML 2.0 (AUTH-01, AUTH-02)

**What "done" looks like:** An admin configures an SSO connection (Google OIDC, GitHub OAuth2, or SAML 2.0 with Okta/Azure AD) in a settings page. New users who authenticate via that IdP are provisioned automatically (JIT) with a default role. Existing email/password accounts continue to work. A user arriving at the login page sees "Sign in with [IdP]" alongside the email form.

### Table Stakes

| Behavior | Notes |
|----------|-------|
| OAuth2 OIDC (Google, GitHub at minimum) | Spring Security OAuth2 Client handles this natively |
| SAML 2.0 IdP configuration (Okta, Azure AD / Entra ID) | Spring Security SAML2 extension |
| JIT user provisioning on first SSO login | Create user record + assign default tenant role |
| SSO must coexist with existing email/password JWT auth | Constraint in PROJECT.md — backward compat required |
| Admin UI to configure IdP (Entity ID, SSO URL, certificate) | Settings page; per-tenant config |
| Login page shows SSO option when configured | Conditional rendering based on tenant SSO config |

**IdP discovery UX:** Tenant-scoped. When a user types their email on the login page, the frontend resolves the tenant from email domain and, if that tenant has SSO configured, redirects to the IdP. No need for global IdP discovery in v1 (that's a SaaS-scale concern).

**JIT provisioning flow:** IdP assertion → Spring Security `AuthenticationSuccessHandler` → check if user exists by email → if not, create `User` with default role (`VIEWER`) → issue Chorus JWT → set tenant context. The existing `JwtTokenService` issues the Chorus-side JWT post-SSO.

### Differentiators

- **Per-tenant SSO isolation**: each tenant configures its own IdP; one deployment supports mixed auth (some tenants SSO, others email/password).
- **SSO-enforced tenants**: admin flag that blocks email/password login for a tenant, forcing all users through IdP.
- **Attribute mapping UI**: map IdP claims (e.g., `department` → Chorus role) without code changes.

### Complexity

**MEDIUM-HIGH.** Spring Security OAuth2 Client and SAML2 extensions are well-understood, but the per-tenant dynamic configuration (loading IdP metadata at runtime per request, not at startup) requires a custom `RelyingPartyRegistrationRepository` backed by the database. That's the hard part. OAuth2 OIDC is 1 week; SAML 2.0 adds another 1-2 weeks for the dynamic registration plumbing.

### Dependencies on Existing Code

- `JwtAuthFilter` — SAML/OIDC flows must bypass JWT filter for SSO callback paths (`/login/oauth2/code/*`, `/login/saml2/sso/*`); these must be added to the `PUBLIC_PATHS` equivalent or handled by Spring Security's filter ordering
- `JwtTokenService` — issues Chorus-side JWT after successful IdP authentication
- `AuthController` — existing login/register endpoints remain; SSO is additive
- `UserController` / user model — JIT provisioning creates user records here
- `TenantContext` — SSO user must land in the correct tenant after auth

### Anti-Features

- **Do not build LDAP sync.** Out of scope per PROJECT.md.
- **Do not require SSO for all tenants.** Email/password must keep working.
- **Do not build a custom IdP.** Chorus Observe is an SP, not an IdP.
- **Do not implement IdP-initiated SSO in v1.** SP-initiated only is sufficient and simpler.

### Build Order Note

AUTH-01 (OAuth2) and AUTH-02 (SAML) can be built sequentially; OAuth2 first since it establishes the Spring Security SSO framework. SCIM (AUTH-03) depends on the user model that JIT provisioning establishes here. AUTH must precede SCIM.

---

## Feature 3: SCIM v2 Provisioning (AUTH-03)

**What "done" looks like:** An enterprise IdP (Okta, Azure AD) can push user create/update/deactivate events to Chorus Observe via `POST/PUT/PATCH/DELETE /scim/v2/Users`. Users provisioned via SCIM are visible in the admin users list. Deactivated users lose access without manual intervention.

### Table Stakes

| Endpoint | Required behavior |
|----------|-----------------|
| `GET /scim/v2/Users` | List with filter (`?filter=userName eq "..."`) |
| `POST /scim/v2/Users` | Create user; return 201 with SCIM User object |
| `GET /scim/v2/Users/{id}` | Return single user |
| `PUT /scim/v2/Users/{id}` | Full replace |
| `PATCH /scim/v2/Users/{id}` | Partial update (Operations array) |
| `DELETE /scim/v2/Users/{id}` | Deactivate (soft-delete, not hard delete) |
| `GET /scim/v2/ServiceProviderConfig` | Capability discovery |
| Bearer token auth on SCIM endpoints | Separate from user JWT; admin-issued token |

SCIM attributes to map: `userName` (email), `name.givenName`, `name.familyName`, `active`, `emails[primary]`, custom `urn:chorus:role` extension for role assignment.

### Differentiators

- **Group push support** (`/scim/v2/Groups`): map IdP groups to Chorus roles/tenants automatically.
- **Conflict resolution**: if a SCIM-created user tries email/password login, surface a clear "managed by IdP, use SSO" message rather than a cryptic auth error.

### Complexity

**MEDIUM.** The SCIM 2.0 spec (RFC 7644) is fixed; there's no ambiguity in what to implement. The main work is the PATCH Operations array parser (replace/add/remove operations on nested attributes), the filter parser for `GET /scim/v2/Users`, and the tenant-scoped bearer token auth. Spring Boot has no native SCIM starter — this is custom REST controllers. Estimate: 1.5–2 weeks.

### Dependencies on Existing Code

- `JwtAuthFilter` — SCIM endpoints use bearer token auth, but not the user JWT; must be excluded from the standard JWT filter and handled by a separate `ScimBearerTokenFilter`
- `UserController` / user persistence — SCIM user creation ultimately uses the same user model; ensure no duplicate creation logic
- `TenantContext` — SCIM operations are tenant-scoped; token must encode tenant ID
- AUTH-01/AUTH-02 (upstream) — JIT-provisioned users and SCIM-provisioned users need consistent user records

### Anti-Features

- **Do not build LDAP/AD directory sync.** Out of scope.
- **Do not implement SCIM Groups in v1 unless Groups are needed for role mapping.** Start with Users only.
- **Do not hard-delete users on SCIM DELETE.** Soft-deactivate; preserve audit trail and historical data.

### Build Order Note

Depends on AUTH-01/02 being done first (user model stabilized). SCIM is the last auth feature to ship.

---

## Feature 4: Prompt Playground (UI-02)

**What "done" looks like:** A user opens a prompt in the Prompts UI, clicks "Open in Playground", and lands on a split-panel page: left panel shows the prompt template with variable inputs; right panel shows model output. The user can change model (from a dropdown of configured providers), adjust temperature/top-p/max-tokens, and hit "Run". Results show: output text, token count, estimated cost, and latency in milliseconds. A "Compare" button opens a second panel running a second model or prompt variant side-by-side.

### Table Stakes

| Behavior | Notes |
|----------|-------|
| Live execution against configured LLM providers | Calls backend; no client-side LLM calls |
| Variable substitution in prompt templates | `{{variable}}` syntax consistent with existing PromptService |
| Latency display (ms, wall clock from API call) | Measured server-side, returned in response header or body |
| Token count display (input + output) | From provider response |
| Cost estimate | `PricingTable` class already exists in codebase |
| Model selector (dropdown of configured providers) | From existing `ModelController` |
| Two-panel model comparison | Side-by-side diff is the minimum; not a full diff tool |

### Differentiators

- **Prompt version selector**: switch between prompt versions within the playground; compare v3 vs v4 side-by-side.
- **Save as new version**: run → tweak → save from playground directly creates a new prompt version.
- **Streaming output**: token-by-token streaming in the right panel (SSE from backend); makes the playground feel responsive.

### Complexity

**MEDIUM.** The backend execution path exists (LLM providers, cost tracking). The work is: (1) a `/api/v1/playground/execute` endpoint that wraps the existing LLM invocation with per-call latency + token measurement; (2) the Next.js split-panel UI; (3) streaming output (Server-Sent Events or fetch streaming). Two-panel comparison requires two concurrent backend calls and UI coordination. Estimate: 2–3 weeks total (1 week backend, 1.5 weeks UI).

### Dependencies on Existing Code

- `PromptController` / `PromptService` — fetches prompt template and version
- `ModelController` — lists available models/providers for the dropdown
- `PricingTable` — cost estimation per token
- `BudgetAwareAgentInvoker` — can re-use for playground execution with budget guard
- Existing LLM provider adapters — playground calls route through same provider stack

### Anti-Features

- **Do not build a full prompt IDE** (syntax highlighting, autocompletion, multi-turn chat debugger). That's scope creep for v1.
- **Do not allow client-side LLM calls.** All execution goes through the Chorus backend; API keys never reach the browser.
- **Do not build streaming in v1 if it blocks delivery.** Static response is acceptable as MVP; streaming is a differentiator, not table stakes.

### Build Order Note

Depends on Prompts UI page (UI-01) being mostly done. Playground is a sub-page of Prompts. UI-01 → UI-02.

---

## Feature 5: Clustering / Insights UI (UI-03)

**What "done" looks like:** A "Clusters" page shows a list of semantic clusters, each card displaying: cluster label (auto-generated topic name), run count, average score, date range, and a "View Representative Trace" button. Clicking a cluster opens a detail view with: the top-3 representative traces, a trend chart (cluster volume over time), and an average score trend. If the cluster's score dropped more than a threshold between periods, a drift alert badge appears on the card.

### Table Stakes

| UI element | Backend source |
|-----------|----------------|
| Cluster label | `TraceCluster.label` |
| Run count | `TraceCluster.runCount` |
| Average score | `TraceCluster.avgScore` |
| Date range | `TraceCluster.periodStart` / `periodEnd` |
| Representative trace link | Query `runs` table for the trace nearest cluster centroid |
| Drift alert badge | Score delta between current period and prior period exceeds threshold |

The `TraceCluster` model already has all fields needed for the card view. The gap is: (a) the representative trace lookup (nearest neighbor to centroid stored in `metadata`), and (b) drift comparison between time periods.

### Differentiators

- **Volume trend sparkline** per cluster card — shows whether the topic is growing, stable, or declining.
- **Cross-cluster comparison**: select two clusters and compare their average score distributions.
- **Topic re-labeling**: admin can override the auto-generated label with a human-readable name; stored alongside `TraceCluster.label`.

### Complexity

**LOW-MEDIUM** for the UI. The `TraceClusteringEngine` and `TraceClusterRepository` already exist. The backend work is: (1) a `/api/v1/clusters` list endpoint with drift delta computation; (2) a `/api/v1/clusters/{id}/representative-traces` endpoint. The UI is a list + detail page in Next.js. Estimate: 1.5 weeks.

### Dependencies on Existing Code

- `TraceCluster` model — `label`, `runCount`, `avgScore`, `avgCost`, `periodStart`, `periodEnd`, `metadata`
- `TraceClusterRepository` — existing persistence; needs a `findByPeriod` or time-range query
- `TraceClusteringEngine` / `EmbeddingClusterer` — produces clusters; runs on schedule already
- `RunController` — fetches representative traces by ID

### Anti-Features

- **Do not expose raw cluster centroids or embedding coordinates** to end users. Topic label + representative traces is the right abstraction.
- **Do not let users manually assign traces to clusters** in v1. Clustering is fully automated.
- **Do not build a 2D cluster map (UMAP visualization)** in v1. It requires UMAP reduction, a canvas renderer, and is expensive to compute. The list view is sufficient.

### Build Order Note

Independent of most other features. Can ship in parallel with AUTH work. Backend cluster API additions are small; UI is the primary work.

---

## Feature 6: Human Annotation Queue (UI-04)

**What "done" looks like:** An annotator lands on a "Review Queue" page. They see a list of assigned items (trace + span pair). Clicking an item opens a full-screen annotation view: the conversation/trace on the left, a scoring panel on the right. The scoring panel shows one or more score dimensions (e.g., Accuracy 1–5, Safety PASS/FAIL) configured by the queue admin. The annotator scores, optionally adds a comment, and clicks "Submit". The item is marked done and the next item loads automatically. A "Skip" button returns the item to the queue. Queue admins see completion stats per annotator.

### Table Stakes

| Behavior | Notes |
|----------|-------|
| Queue shows assigned items per user | Assignment either round-robin or manual |
| Per-item scoring with configured dimensions | Score types: numeric (1–5), binary (PASS/FAIL), categorical (label set) |
| Comment field | Maps to `Feedback.comment` |
| Skip (return to queue) | Item remains in queue, not counted as reviewed |
| Submit creates Feedback record | Maps to existing `FeedbackService.submitFeedback()` |
| Progress indicator | "X of Y reviewed" per queue session |
| Admin view of queue completion stats | Items done / total, per annotator breakdown |

The `Feedback` model (`feedbackId`, `runId`, `spanId`, `score`, `label`, `comment`, `source`) already captures what an annotator produces. The gap is: queue management (which items, in what order, assigned to whom) and the annotation UX.

### Differentiators

- **Pre-filled LLM score**: show the existing LLM-as-judge score alongside the annotation form so annotators can agree/disagree explicitly — trains disagreement signals.
- **Batch assignment**: admin assigns a date range of low-scored or unannotated traces to a queue automatically.
- **Inter-annotator agreement display** (admin view only): when two annotators reviewed the same item, show Cohen's kappa — but do NOT surface this to annotators themselves.

### Complexity

**MEDIUM.** The `Feedback` model and `FeedbackService` exist, but the queue management layer (item pool, assignment, state tracking — pending/in-progress/done/skipped) is new. This requires a new `AnnotationQueue` model and service. The UI annotation form is medium complexity (multi-dimension scoring, keyboard shortcuts for flow). Estimate: 2–2.5 weeks (1 week backend queue model, 1–1.5 weeks UI).

### Dependencies on Existing Code

- `FeedbackService.submitFeedback()` — annotation submission calls this; `source` field = `"human"`
- `FeedbackController` — existing POST `/api/v1/runs/{runId}/feedback`; queue UI calls this per submission
- `Feedback` model — `score`, `label`, `comment` are all present
- `RunController` / `SpanController` — fetch trace content to display in annotation view
- Multi-tenant RBAC — annotator role vs queue admin role must be enforced

### Anti-Features

- **Do not build inter-annotator agreement scoring in v1.** Show it only to admins, and only as a read stat — not as a quality gate.
- **Do not build a configurable annotation schema editor** (drag-and-drop dimension builder). YAML/JSON config import is sufficient for v1.
- **Do not gamify** (leaderboards, points, streaks). Enterprise annotators are domain experts, not crowdworkers.

### Build Order Note

Backend queue model can be built independently. UI depends on having trace display working (already exists in Run/Span views). Can ship after UI-01 since it shares the trace display component.

---

## Feature 7: RAG Metrics Dashboard (UI-06)

**What "done" looks like:** A "RAG" page shows time-series charts for key retrieval and generation quality metrics. Metrics are computed per RAG span and aggregated by day/week. Charts include: context precision, context recall, faithfulness, answer relevance (RAGAS-style), retrieval latency (p50/p95), chunk hit rate, and average similarity score. Clicking a data point drills into the specific spans driving a metric anomaly.

### Table Stakes

| Metric | Definition | Backend source |
|--------|-----------|----------------|
| Retrieval latency p50/p95 | Wall-clock time for retrieval step | `RagQuery.latencyMs` |
| Chunk hit rate | % of queries where retrieved chunks contain the answer | Requires ground truth or proxy |
| Average similarity score | Mean of top-k cosine similarities | `RagQuery.similarityScores` (JSON array) |
| Context precision | Proportion of retrieved chunks relevant to query | LLM-judge or heuristic |
| Faithfulness | Output grounded in retrieved context | LLM-judge |
| Answer relevance | Output addresses the query | LLM-judge |
| Context recall | Retrieved chunks cover all relevant aspects | Requires ground truth |

**Pragmatic note:** True context precision/recall require ground truth or an LLM judge running over every RAG span. For v1, compute: latency, similarity scores, and hit rate (which are instrumentable without ground truth), and treat faithfulness/context precision as "LLM-eval metrics" that only appear when an evaluator has been run. Do not block the dashboard on LLM-computed metrics being available for all spans.

### Differentiators

- **Retriever vs generator split**: separate latency breakdown showing how much time the retrieval step vs the generation step consumes.
- **Chunk-level drill-down**: click on a low-faithfulness data point → see the specific retrieved chunks + output for that span.
- **Per-model comparison**: if multiple embedding models are in use, compare hit rate and similarity score by model.

### Complexity

**MEDIUM.** The `RagQuery` model captures `latencyMs` and `similarityScores`. The gap is: (1) aggregation queries over `rag_queries` table (ClickHouse is ideal for this); (2) LLM-eval metric computation (re-use `EvalService`); (3) charting UI (time-series charts in Next.js with shadcn). Estimate: 2 weeks (1 backend aggregation + 1 frontend charts).

### Dependencies on Existing Code

- `RagQuery` model — `query`, `retrievedChunks`, `similarityScores`, `latencyMs`
- `RagQueryRepository` — source of truth; needs time-series aggregation queries
- `EvalService` / `AgentInvokerJudgeScorer` — for faithfulness + context precision when computed
- `MetricController` — check if aggregation endpoints already exist; extend rather than duplicate
- ClickHouse (dual-store) — prefer ClickHouse for time-series aggregations over PostgreSQL

### Anti-Features

- **Do not block the dashboard on LLM-computed metrics being present** for all spans. Show what's available; grey out unavailable metrics.
- **Do not implement RAGAS as a library dependency.** Implement the metric definitions natively using existing LLM-judge infrastructure.
- **Do not show per-chunk content in the main dashboard view.** Keep it in drill-down only.

### Build Order Note

Independent. Can ship after the dashboard structure pattern is established by earlier UI pages. ClickHouse aggregation queries are the critical path item.

---

## Feature 8: Real Parquet Export (COMP-01)

**What "done" looks like:** When an export job with `format: PARQUET` completes, the output file is a valid Apache Parquet file (not JSON with a `.parquet` extension). The schema covers the primary exportable entities. The file is partitioned by date and project (tenant). It can be read by pandas, Spark, DuckDB, and Athena without schema errors.

### Schema (recommended)

For `runs` resource type:

| Column | Type | Notes |
|--------|------|-------|
| `run_id` | STRING | Primary key |
| `tenant_id` | STRING | Partition key component |
| `project_id` | STRING | Partition key component |
| `name` | STRING | |
| `status` | STRING (enum) | |
| `input` | STRING (JSON) | Serialize complex inputs as JSON string |
| `output` | STRING (JSON) | |
| `latency_ms` | INT64 | |
| `token_input` | INT64 | |
| `token_output` | INT64 | |
| `cost_usd` | DOUBLE | |
| `model` | STRING | |
| `eval_score` | DOUBLE | nullable |
| `tags` | STRING (JSON array) | |
| `started_at` | INT64 (epoch ms) | |
| `ended_at` | INT64 (epoch ms) | |
| `export_batch_id` | STRING | The export job ID for lineage |

**Partition strategy:** Hive-style partition directories — `tenant={tenant_id}/year={YYYY}/month={MM}/day={DD}/`. This is compatible with AWS Athena, Spark, and DuckDB partition discovery without manual schema registration.

### Table Stakes

| Behavior | Notes |
|----------|-------|
| Valid Parquet file (not JSON stub) | Fix the TODO at `ExportService.java:110-114` |
| Schema consistent across export jobs | Same columns in same order every time |
| Null safety (nullable columns handled correctly) | Parquet null encoding vs absent column |
| Readable by common tools (pandas, DuckDB) | Basic interop test |
| Date partitioning in file path | Required for downstream query performance |

### Differentiators

- **Columnar type preservation**: store timestamps as `INT64` epoch millis with `isAdjustedToUTC=true` logical type annotation, not as strings. This enables time-range pushdown in query engines.
- **Snappy compression by default**: reduces file size ~3-5x with negligible CPU cost. Make codec configurable (GZIP, ZSTD options).
- **Schema evolution**: version the Parquet schema and write the schema version into Parquet file metadata — forward-compatibility for future columns.

### Complexity

**MEDIUM.** The `ExportJob` model, `ExportService`, and async execution framework already exist. The work is: (1) add Apache Parquet + Arrow dependency to `build.gradle.kts`; (2) implement a `ParquetWriter` class that maps `Map<String, Object>` rows to typed Parquet schema; (3) replace the stub at `ExportService.java:110-114`. The schema design is non-trivial (type mapping from JDBC `Object` to Parquet types), but the pattern is well-documented. Estimate: 1–1.5 weeks.

### Dependencies on Existing Code

- `ExportService` — the stub is at line 110-114; this is the exact insertion point
- `ExportJob.Format.PARQUET` — enum value exists; just needs real implementation
- `ExportJobRepository` — no changes needed
- `ExportController` — no changes needed

**Gradle dependency to add:**
```
implementation("org.apache.parquet:parquet-avro:1.14.x")
implementation("org.apache.hadoop:hadoop-common:3.x") // or use parquet-column standalone
```
Prefer `parquet-column` + `parquet-hadoop` over the Avro bridge if Avro is not already a dependency — reduces transitive dependencies.

### Anti-Features

- **Do not write JSON into .parquet files.** The existing stub must be replaced, not extended.
- **Do not use Avro as an intermediate format** unless Avro is already a project dependency.
- **Do not implement streaming Parquet write in v1** (writing Parquet to S3 directly in a stream). Write locally, then upload. Streaming multipart upload is a future optimization.

### Build Order Note

Self-contained. Build before S3 export (COMP-02) since S3 export uploads what Parquet export produces.

---

## Feature 9: S3 Export (COMP-02)

**What "done" looks like:** An operator configures an S3 destination (bucket, prefix, AWS credentials or IAM role ARN) in the Export settings. When an export job has `destination: S3`, the completed local file is uploaded to `s3://{bucket}/{prefix}/{tenant_id}/{year}/{month}/{day}/{jobId}-{resourceType}.parquet`. The operator can also schedule recurring exports (daily at midnight UTC) that run automatically.

### Table Stakes

| Behavior | Notes |
|----------|-------|
| Upload completed export file to S3 | After local Parquet write completes |
| Configurable bucket + prefix per tenant | Stored in tenant export config |
| AWS credentials via access key or environment IAM role | Support both; prefer IAM role for self-hosted k8s |
| Scheduled daily export | Spring `@Scheduled` cron job |
| On-demand export with S3 destination | User triggers via UI; result in S3, not local download |
| Upload status reflected in ExportJob | Status COMPLETED after S3 upload confirmed |

**File naming:** `{prefix}/{tenant_id}/year={YYYY}/month={MM}/day={DD}/{jobId}-{resourceType}.parquet` — Hive-compatible path structure that matches the Parquet partition strategy.

### Differentiators

- **STS AssumeRole support**: for customers that want to grant cross-account access without long-lived credentials.
- **Upload verification**: verify S3 ETag matches local MD5 after upload; surface checksum in the ExportJob record.
- **Presigned URL for local exports**: when `destination: FILE`, return a presigned download URL instead of serving the file through the API server — avoids large file streaming through the app tier.

### Complexity

**LOW-MEDIUM.** The `ExportJob.Destination.S3` enum and `destinationPath` field already exist. The work is: (1) add AWS SDK v2 dependency; (2) implement `S3UploadService` that accepts a local Path + S3 config and uploads; (3) plug into `ExportService.executeExport()` post-write; (4) add the scheduling cron job. Estimate: 1 week.

### Dependencies on Existing Code

- `ExportService` — plug S3 upload into the `executeExport` flow after `exportToFile()` completes
- `ExportJob.Destination.S3` — enum value exists
- `ExportJob.destinationPath` — stores the S3 URI post-upload
- `ChorusObserveProperties` — add S3 config (bucket, prefix, region, credentials) to the existing properties class
- COMP-01 (Parquet export) — S3 export primarily uploads Parquet files; build after Parquet works

**Gradle dependency to add:**
```
implementation("software.amazon.awssdk:s3:2.x")
```

### Anti-Features

- **Do not build S3-compatible (MinIO) support as a separate code path.** AWS SDK v2 works with MinIO by setting a custom endpoint URL — document this, don't code it separately.
- **Do not serve large files through the application tier.** Return a presigned URL or S3 URI; let the user/tool download directly.
- **Do not make S3 config global.** Keep it per-tenant so different tenants can export to different buckets.

### Build Order Note

Depends on COMP-01 (Parquet export). Build second.

---

## Feature 10: CI/CD Eval Gate (COMP-05)

**What "done" looks like:** A GitHub Actions workflow calls the Chorus Observe API, runs an eval against a named dataset + evaluator config, waits for completion, and fails the workflow if the score drops below a configured threshold. The config is a YAML block in the workflow file. The action exits non-zero on regression.

### Table Stakes

| Behavior | Notes |
|----------|-------|
| GitHub Action that triggers an eval run | POST to `/api/v1/eval-runs`; poll for completion |
| Configurable thresholds in YAML | Per-evaluator absolute floor; optional % drop from baseline |
| Fail the action on regression | Non-zero exit code; job fails |
| Post results as PR comment | Show per-evaluator scores and delta vs baseline |
| Support API key auth | The action uses a Chorus API key, not user JWT |

**Threshold config schema (recommended YAML):**
```yaml
- uses: chorus-observe/eval-gate-action@v1
  with:
    api_url: ${{ secrets.CHORUS_URL }}
    api_key: ${{ secrets.CHORUS_API_KEY }}
    dataset_id: "ds-abc123"
    evaluator_ids: "ev-factuality,ev-safety"
    thresholds: |
      ev-factuality:
        min_score: 0.80          # absolute floor
        max_drop_pct: 5          # fail if drops >5% vs baseline_run_id
      ev-safety:
        min_score: 0.95          # hard floor, no relative threshold
    baseline_run_id: ${{ vars.EVAL_BASELINE_RUN }}
```

This mirrors Langfuse's approach (threshold variable per evaluator) and Braintrust's PR comment pattern.

### Differentiators

- **Per-evaluator thresholds**: different scorers have different acceptable ranges (safety must be 0.95+; factuality can tolerate 0.80). Flat thresholds across all evaluators are too blunt.
- **Baseline pinning**: compare against a named baseline run (the last green main branch run), not just an absolute floor. This catches regressions even when the floor is met.
- **Soft gate mode**: `fail_on_regression: false` — posts a comment with scores but does not fail the job. Useful for early adoption.

### Complexity

**MEDIUM.** Two components: (1) a GitHub Action (TypeScript, ~200 lines) that wraps the Chorus API — create eval run, poll status, compare scores, post comment, exit; (2) the Chorus backend already has `EvalController` and comparison (`compareRuns` endpoint). The main backend addition is a stable "get eval run result summary" endpoint if one doesn't exist. Estimate: 1–1.5 weeks (mostly the Action wrapper + docs).

### Dependencies on Existing Code

- `EvalController` — `POST /api/v1/eval-runs`, `GET /api/v1/eval-runs/{id}`, `GET /api/v1/eval-runs/{id}/results`
- `EvalService.compareRuns()` — baseline vs current comparison; already exists
- `EvalService.RegressionReport` — already exists; expose as API response
- `ApiKeyAuthFilter` — the GitHub Action authenticates with an API key, not a user JWT; already supported
- `EvalController.SubmitEvalRequest` — the action POSTs this payload

### Anti-Features

- **Do not invent a custom DSL for threshold config.** YAML key-value thresholds are sufficient and parseable by any CI system.
- **Do not require Chorus to push results to GitHub.** Pull model: the Action polls Chorus. No webhooks to GitHub from Chorus in v1.
- **Do not support GitLab CI / Azure Pipelines in v1.** Ship GitHub Actions; the API is the same for other CI systems and teams can adapt the shell commands.
- **Do not make the action synchronous-blocking.** Use async polling with a configurable `timeout_minutes` (default 10).

### Build Order Note

Depends only on `EvalController` being stable (already is). The GitHub Action is a separate repo/artifact. Can build in parallel with UI work. Backend changes are minimal.

---

## Feature Dependency Graph

```
COMP-01 (Parquet) → COMP-02 (S3 export)
AUTH-01 (OAuth2) → AUTH-02 (SAML) → AUTH-03 (SCIM)
UI-01 (Prompts page) → UI-02 (Playground)
DatasetService (existing) → EVAL-01 (Auto-eval gen)
EvalController (existing) → COMP-05 (CI/CD gate)
UI-03, UI-04, UI-06 are largely independent
```

## Suggested Build Order

| Priority | Feature | Rationale |
|----------|---------|-----------|
| 1 | AUTH-01 (OAuth2 SSO) | Unblocks SAML + SCIM; enterprise deals gated on this |
| 2 | COMP-05 (CI/CD gate) | High visible ROI; minimal backend work; unblocks dev workflow adoption |
| 3 | UI-01 (Prompts page) + UI-02 (Playground) | Highest user-facing gap; Playground depends on Prompts |
| 4 | COMP-01 (Parquet export) → COMP-02 (S3 export) | Sequential; Parquet first |
| 5 | EVAL-01 (Auto-eval gen) | Highest complexity; start backend early, review UI later |
| 6 | AUTH-02 (SAML) → AUTH-03 (SCIM) | Sequential; SAML first |
| 7 | UI-03 (Clustering UI) | Backend complete; primarily UI work |
| 8 | UI-04 (Annotation queue) | Needs new queue model; medium backend + UI |
| 9 | UI-06 (RAG metrics) | Needs ClickHouse aggregation design; medium work |

---

## Sources

- [Braintrust: Online scoring and production trace → eval case workflow](https://www.braintrust.dev/articles/how-to-eval)
- [Braintrust: CI/CD eval gate GitHub Action](https://github.com/marketplace/actions/braintrust-eval)
- [Langfuse: Annotation Queues documentation](https://langfuse.com/docs/evaluation/evaluation-methods/annotation-queues)
- [Langfuse: Experiments CI/CD](https://langfuse.com/docs/evaluation/experiments/experiments-ci-cd)
- [Langfuse: LLM-as-a-Judge evaluation](https://langfuse.com/docs/evaluation/evaluation-methods/llm-as-a-judge)
- [RAGAS metrics: available metrics list](https://docs.ragas.io/en/stable/concepts/metrics/available_metrics/)
- [SCIM v2 with Okta and Azure AD integration guide](https://ssojet.com/blog/integrating-scim-with-identity-providers-your-complete-guide-to-okta-and-azure-ad)
- [JIT provisioning vs SCIM: use cases](https://ssojet.com/blog/how-to-implement-just-in-time-jit-user-provisioning-with-sso-and-scim)
- [Arize Phoenix: clustering and drift detection](https://galileo.ai/blog/galileo-vs-arize)
- [Apache Parquet schema and partitioning](https://arrow.apache.org/docs/python/parquet.html)
- [Datadog LLM Observability annotation queues](https://docs.datadoghq.com/llm_observability/evaluations/annotation_queues/)
