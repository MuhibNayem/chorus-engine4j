---
gsd_state_version: 1.0
milestone: v1.0
milestone_name: Enterprise Feature Parity
status: planning
last_updated: "2026-05-23T00:00:00.000Z"
last_activity: 2026-05-23
progress:
  total_phases: 6
  completed_phases: 0
  total_plans: 0
  completed_plans: 0
  percent: 0
---

# Project State

## Project Reference

See: .planning/PROJECT.md (updated 2026-05-23)

**Core value:** Give enterprise teams complete visibility into every LLM call, agent decision, and evaluation outcome — fully self-hosted, with no feature gates.
**Current focus:** Phase 1 — Security Foundation (ready to plan)

## Current Position

Phase: 1 of 6 (Security Foundation)
Plan: 0 of TBD in current phase
Status: Phase 1 complete — ready for Phase 2
Last activity: 2026-05-23 — Phase 1 executed (4 plans, 28 tests, all passing)

Progress: [░░░░░░░░░░] 0%

**Phase 1:** 4 plans | 3 waves | Status: ✓ Complete
**Phase 2:** 0 plans | TBD | Status: Ready to discuss

## Performance Metrics

**Velocity:**
- Total plans completed: 0
- Average duration: —
- Total execution time: 0 hours

**By Phase:**

| Phase | Plans | Total | Avg/Plan |
|-------|-------|-------|----------|
| - | - | - | - |

**Recent Trend:**
- Last 5 plans: —
- Trend: —

*Updated after each plan completion*

## Accumulated Context

### Decisions

Decisions are logged in PROJECT.md Key Decisions table.
Recent decisions affecting current work:

- Roadmap: SAML via Spring Security SAML2 (avoids third-party auth vendors)
- Roadmap: Teams alerting uses Power Automate Adaptive Card webhook only (O365 connectors retired May 2026)
- Roadmap: MinIO supported via AWS SDK v2 custom endpoint override — no separate code path
- Roadmap: SCIM LDAP sync deferred; SCIM v2 covers the need

### Blockers/Concerns

- [Phase 1] Spring Security 6.5.0 pins REMOVED in hotfix commit 38e01ea
- [Phase 3] Cross-tenant data leak FIXED in hotfix commit 38e01ea

### Pending Todos

None yet.

## Deferred Items

| Category | Item | Status | Deferred At |
|----------|------|--------|-------------|
| Auth | Per-tenant SSO enforcement (block email/password for SSO-only tenants) | v1.1 | Roadmap |
| Auth | IdP-initiated SAML SSO | v1.1 | Roadmap |
| Auth | SAML attribute mapping (IdP claim to Chorus role) | v1.1 | Roadmap |
| Auth | SCIM Groups push for role mapping | Deferred — no customer demand yet | Roadmap |
| Export | S3 presigned URL for large file downloads | v1.1 | Roadmap |
| Export | STS AssumeRole cross-account for S3 | v1.1 | Roadmap |
| Eval | Coverage clustering (embedding diversity) for smarter sampling | v1.1 | Roadmap |
| Eval | Failure-first sampling (weight toward low-scoring spans) | v1.1 | Roadmap |

## Session Continuity

Last session: 2026-05-23
Stopped at: Phase 1 context gathered
Resume file: .planning/phases/01-security-foundation/01-CONTEXT.md
