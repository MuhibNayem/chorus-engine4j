# Phase 1: Security Foundation - Discussion Log

> **Audit trail only.** Do not use as input to planning, research, or execution agents.
> Decisions are captured in CONTEXT.md — this log preserves the alternatives considered.

**Date:** 2026-05-23
**Phase:** 1-Security Foundation
**Areas discussed:** Missing security tests, Filter chain architecture for Phase 2, RBAC duality: @PreAuthorize vs custom filter

---

## Missing security tests

| Option | Description | Selected |
|--------|-------------|----------|
| Write new security tests | Phase 1 includes writing JWT login/refresh/logout integration tests, API key auth tests, and @PreAuthorize method-security tests against Security 7.0.0. ~4-6 test classes. | ✓ |
| Adjust success criteria only | Change criteria to compilation + dependency verification only. Defer tests to Phase 2. | |
| Minimal smoke test only | One @SpringBootTest verifying context loads. Fast but doesn't exercise auth flows. | |

**User's choice:** Write new security tests (Recommended)
**Notes:** User explicitly wants comprehensive test coverage as part of Phase 1.

---

| Option | Description | Selected |
|--------|-------------|----------|
| Hand-written fakes + plain JUnit | Consistent with existing tests: in-memory fakes, direct unit tests. No Spring test framework, no MockMvc. | ✓ |
| @SpringBootTest + TestRestTemplate | Full integration tests against running server with H2. New pattern in codebase. | |
| Hybrid: fakes for unit, @SpringBootTest for @PreAuthorize | JwtTokenService/filter tests use fakes. One @SpringBootTest for @PreAuthorize. | |

**User's choice:** Hand-written fakes + plain JUnit (Recommended)
**Notes:** Aligns with existing codebase pattern (InMemoryRunRepository, etc.).

---

| Option | Description | Selected |
|--------|-------------|----------|
| Core 4: JWT + API key + @PreAuthorize + PasswordEncoder | JwtTokenService, JwtAuthFilter, ApiKeyAuthFilter, @PreAuthorize, BCryptPasswordEncoder. ~4 test classes. | |
| Everything including RBAC filter | Add RbacAuthorizationFilter tests too. May expose inactive-RBAC issue. | ✓ |
| JWT only (minimum viable) | Just JwtTokenService and JwtAuthFilter. Fastest but leaves gaps. | |

**User's choice:** Everything including RBAC filter
**Notes:** User wants full coverage including the currently inactive RBAC filter.

---

| Option | Description | Selected |
|--------|-------------|----------|
| Document current behavior, fix later | Assert no-op behavior, add TODO, fix in Phase 2. | |
| Fix the interceptor as part of test writing | Implement missing interceptor/annotation while writing tests. Expands Phase 1 scope. | ✓ |
| Skip RBAC filter tests for now | Don't test RbacAuthorizationFilter in Phase 1. | |

**User's choice:** Fix the interceptor as part of test writing
**Notes:** User wants the RBAC filter to be functional and testable in Phase 1, not deferred.

---

## Filter chain architecture for Phase 2

| Option | Description | Selected |
|--------|-------------|----------|
| Introduce SecurityFilterChain now | Create SecurityFilterChain bean wiring custom filters. Foundation for Phase 2 OAuth2/SAML. | ✓ |
| Keep FilterRegistrationBean for Phase 1 | Minimal change. Defer SecurityFilterChain to Phase 2. | |
| Hybrid: SecurityFilterChain for JWT/RBAC, keep API key as FilterRegistrationBean | Splits auth model but keeps API key decoupled. | |

**User's choice:** Introduce SecurityFilterChain now (Recommended)
**Notes:** User sees Phase 1 as the right time to establish the security foundation.

---

| Option | Description | Selected |
|--------|-------------|----------|
| Register custom filters in SecurityFilterChain | Keep filters as-is, add via addFilterBefore/addFilterAfter. Minimal code change. | ✓ |
| Migrate JWT to Spring Security resource server | Replace custom JwtAuthFilter with BearerTokenAuthenticationFilter + JwtDecoder. More idiomatic but more change. | |
| Wrap in AuthenticationProvider pattern | Extract JWT logic into custom AuthenticationProvider. Medium complexity. | |

**User's choice:** Register custom filters in SecurityFilterChain (Recommended)
**Notes:** Preserve existing filter logic, minimize Phase 1 churn.

---

| Option | Description | Selected |
|--------|-------------|----------|
| SecurityFilterChain for public paths, @PreAuthorize for admin, custom filter for fine-grained | Clean separation of concerns. | ✓ |
| Consolidate everything into SecurityFilterChain + @PreAuthorize | Remove RbacAuthorizationFilter entirely. Verbost but eliminates custom filter. | |
| Minimal change: keep all three as-is | SecurityFilterChain as shell only. Defer consolidation. | |

**User's choice:** SecurityFilterChain for public paths, @PreAuthorize for admin, custom filter for fine-grained
**Notes:** Layered approach: path-level (chain), role-level (@PreAuthorize), permission-level (custom filter).

---

| Option | Description | Selected |
|--------|-------------|----------|
| Single chain with dual auth support | One SecurityFilterChain for both API key and JWT. Simpler but must be order-aware. | |
| Multiple chains by path | One for `/api/*` (JWT + RBAC), one for `/v1/*` (API key). Clean separation. | ✓ |
| Multiple chains by authentication type | Branch within chain by header presence. Most flexible but complex. | |

**User's choice:** Multiple chains by path (Recommended)
**Notes:** API keys for ingestion (`/v1/*`), JWT for app APIs (`/api/*`).

---

## RBAC duality: @PreAuthorize vs custom filter

| Option | Description | Selected |
|--------|-------------|----------|
| Custom annotation + HandlerInterceptor | @RequirePermission annotation + HandlerInterceptor setting request attribute. Most Spring-idiomatic. | ✓ |
| Aspect-oriented programming (AOP) | @Aspect intercepts annotated methods, checks permissions inline. Replaces filter entirely. | |
| Manual controller logic | Each controller checks permissions manually. Verbose and error-prone. | |

**User's choice:** Custom annotation + HandlerInterceptor
**Notes:** Explicit annotation-based approach, consistent with @PreAuthorize pattern.

---

| Option | Description | Selected |
|--------|-------------|----------|
| Yes, migrate to request attributes | Update filter to read request.getAttribute('requiredPermission'). Prevents client spoofing. | ✓ |
| Keep header but validate origin | Add validation for internal source. More complex, still vulnerable. | |
| Remove filter, move check into interceptor | Interceptor does permission check directly. Simpler pipeline. | |

**User's choice:** Yes, migrate to request attributes (Recommended)
**Notes:** Correct pattern for server-side metadata. Eliminates header spoofing risk.

---

| Option | Description | Selected |
|--------|-------------|----------|
| @PreAuthorize for admin, @RequirePermission for everything else | No overlap. Simple mental model. | |
| Replace @PreAuthorize with @RequirePermission everywhere | Unified annotation system. Requires verifying method security still works. | |
| Both annotations, layered | Defense-in-depth. Both checks run. | ✓ |

**User's choice:** Both annotations, layered (Recommended)
**Notes:** Safest approach — @PreAuthorize provides coarse-grained role check, @RequirePermission provides fine-grained permission check.

---

| Option | Description | Selected |
|--------|-------------|----------|
| Single permission only | @RequirePermission(String value) takes one permission. Simplest. | ✓ |
| Multiple with AND logic | @RequirePermission(String[] value) — all required. More flexible. | |
| Multiple with OR logic | @RequirePermission(String[] value, RequireMode mode). Most flexible but over-engineered. | |

**User's choice:** Single permission only (Recommended)
**Notes:** Current needs don't require multi-permission support. Can extend later.

---

## Claude's Discretion

None — all decisions were user-selected.

## Deferred Ideas

None — discussion stayed within phase scope.
