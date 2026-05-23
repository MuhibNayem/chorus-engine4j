# Chorus Observe

## What This Is

Chorus Observe is a self-hosted, enterprise-hardened LLM observability platform with deep agent-specific features. It provides trace/span ingestion (OTLP native), prompt management, evaluations, runtime guardrails, multi-agent provenance tracking, and a Next.js frontend (Chorus Studio). Built in Java/Spring Boot with dual-storage (PostgreSQL + ClickHouse), it targets engineering teams that need full data control and cannot use cloud-only SaaS solutions.

## Core Value

Give enterprise teams complete visibility into every LLM call, agent decision, and evaluation outcome — fully self-hosted, with no feature gates.

## Current Milestone: v1.0 Enterprise Feature Parity

**Goal:** Close all actionable competitive gaps vs. Langfuse/Braintrust/Galileo, turning Chorus Observe into a self-hosted enterprise-grade LLM observability platform.

**Target features:**
- Missing UI Pages: Prompts/Playground, Clustering Insights, Feedback Queue, Export, RAG Metrics
- Automated Eval Generation: LLM-driven test case generation from production traces
- Enterprise Auth: SSO/SAML/OAuth2, SCIM provisioning
- Completions: Real Parquet/S3 export, Microsoft Teams dispatcher, hallucination evaluator, CI/CD eval gate
- UI Polish: Mobile-responsive layouts, UX improvements

## Requirements

### Validated

- ✓ OTLP trace/span ingestion (HTTP + gRPC) — existing
- ✓ Run/span waterfall UI — existing
- ✓ LLM-as-judge evaluations (AgentInvokerJudgeScorer) — existing
- ✓ Prompt management + versioning + tagging — existing
- ✓ Prompt A/B testing with statistical analysis (WelchTTest) — existing
- ✓ Conversation clustering (TraceClusteringEngine + EmbeddingClusterer) — existing
- ✓ Spring Boot auto-instrumentation starter (@EnableChorus) — existing
- ✓ Runtime guardrails: TieredGuardrailEngine (keyword, regex, LLM-judge, embedding) — existing
- ✓ Budget tracking + enforcement (BudgetAwareAgentInvoker) — existing
- ✓ Dataset creation from production traces — existing
- ✓ Feedback/annotation API (FeedbackService + FeedbackController) — existing
- ✓ PII redaction (PiiRedactionEngine) — existing
- ✓ Slack + PagerDuty alert dispatchers — existing
- ✓ Multi-tenant RBAC, JWT auth, method-level security — existing
- ✓ Provenance DAG (multi-agent causal chain) — existing

### Active

- [ ] UI-01: Prompts UI page (list, create, version history, A/B test launch)
- [ ] UI-02: Prompt playground page (live execution, compare variants)
- [ ] UI-03: Clustering/Insights page (cluster view, representative traces)
- [ ] UI-04: Feedback queue page (review queue, annotation workflow)
- [ ] UI-05: Export page (configure jobs, download, S3 destination)
- [ ] UI-06: RAG metrics page (retrieval scores, latency, hit-rate charts)
- [ ] EVAL-01: Automated eval generation from production traces
- [ ] AUTH-01: SSO via OAuth2 (Google, GitHub, OIDC)
- [ ] AUTH-02: SAML 2.0 integration (Okta, Azure AD)
- [ ] AUTH-03: SCIM v2 provisioning endpoint
- [ ] COMP-01: Real Apache Parquet export (not JSON fallback)
- [ ] COMP-02: S3 export destination (AWS SDK integration)
- [ ] COMP-03: Microsoft Teams alert dispatcher
- [ ] COMP-04: Hallucination detection exposed as guardrail evaluator
- [ ] COMP-05: CI/CD eval gate (GitHub Action that fails on eval regression)
- [ ] UX-01: Mobile-responsive UI (all pages, sidebar collapse)

### Out of Scope

- SCIM LDAP sync — complexity without clear demand; SCIM v2 is sufficient
- Native mobile app — Tailwind responsive web covers the use case
- Multi-region failover — self-hosted; infra is user's responsibility

## Context

**Backend:** Java/Spring Boot, 256+ source files, PostgreSQL + ClickHouse dual-store, gRPC + REST APIs, Spring Security RBAC.
**Frontend:** Next.js 14, Tailwind CSS, shadcn/ui, TypeScript — 14 existing pages/routes.
**Gap audit (May 2026):** 10/18 items already have backend implementations. Primary work is UI wiring + 2 truly missing features (automated eval generation, SSO/SAML).
**Competitive context:** Langfuse, LangSmith, Braintrust, Galileo are the primary benchmarks. Chorus Observe is ahead on self-hosting, RBAC, and provenance DAG; behind on eval automation, prompt playground UI, and SSO.

## Constraints

- **Tech stack**: Java 21 / Spring Boot 3.x backend; Next.js 14 / TypeScript frontend — no language changes
- **Self-hosted**: All features must work without external cloud dependencies (optional S3/Teams config)
- **No feature gates**: Enterprise features must be available in open deployment
- **Auth backward compat**: SSO must coexist with existing JWT email/password auth

## Key Decisions

| Decision | Rationale | Outcome |
|----------|-----------|---------|
| Dual-write span store (PG + ClickHouse) | ClickHouse for analytics queries, PG for relational integrity | — Pending evaluation |
| TieredGuardrailEngine over single evaluator | Performance: fast rules first, slow LLM-judge last | ✓ Good |
| Spring Boot starter for auto-instrumentation | One annotation setup matches LangSmith's DX | — Pending adoption data |
| SAML via Spring Security SAML2 | Avoids third-party auth vendors; native Spring integration | — Pending |

## Evolution

This document evolves at phase transitions and milestone boundaries.

**After each phase transition** (via `/gsd-transition`):
1. Requirements invalidated? → Move to Out of Scope with reason
2. Requirements validated? → Move to Validated with phase reference
3. New requirements emerged? → Add to Active
4. Decisions to log? → Add to Key Decisions
5. "What This Is" still accurate? → Update if drifted

**After each milestone** (via `/gsd-complete-milestone`):
1. Full review of all sections
2. Core Value check — still the right priority?
3. Audit Out of Scope — reasons still valid?
4. Update Context with current state

---
*Last updated: 2026-05-23 — Milestone v1.0 started*
