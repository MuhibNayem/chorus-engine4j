# Phase 1: Security Foundation - Context

**Gathered:** 2026-05-23
**Status:** Ready for planning

<domain>
## Phase Boundary

Spring Security 7.0.0 is the active version on the classpath and all existing auth mechanisms (JWT, API key, `@PreAuthorize` method security, custom RBAC filter) work without regressions. This phase unblocks all subsequent auth work (Phase 2: Enterprise Authentication).

**Requirements:** SEC-01, SEC-02
- SEC-01: Remove explicit Spring Security 6.5.0 version pins from `chorus-observe-server/build.gradle.kts` so the Boot 4.0.0 BOM resolves Security 7.0.0
- SEC-02: All existing JWT/RBAC auth tests pass against Security 7.0.0 with no filter-chain regressions

**Success criteria (from ROADMAP.md):**
1. Running `./gradlew dependencies` shows `spring-security-core:7.0.x` (no 6.5.0 artifacts)
2. All existing JWT login, token refresh, and logout integration tests pass green against Security 7.0.0
3. All existing RBAC method-security tests (`@PreAuthorize`, role-gated endpoints) pass without filter-chain regressions
4. The build compiles cleanly with zero Security-version conflict warnings in the Gradle output

**Note:** SEC-01 was completed as a hotfix before this discussion (pins removed, build passes).
</domain>

<decisions>
## Implementation Decisions

### Missing Security Tests
- **D-01:** Phase 1 includes writing new security tests from scratch. The codebase currently has zero dedicated security tests.
- **D-02:** Test style: hand-written in-memory fakes + plain JUnit 5 + AssertJ. Consistent with existing test suite pattern (e.g., `InMemoryRunRepository`). No `@SpringBootTest`, no Mockito, no MockMvc.
- **D-03:** Test coverage must include: `JwtTokenService` (generate, parse, expiry, invalid signature), `JwtAuthFilter` (public path bypass, missing token rejection, valid token acceptance), `ApiKeyAuthFilter` (valid key, revoked key, missing key), `@PreAuthorize` on admin controllers, `BCryptPasswordEncoder`, and `RbacAuthorizationFilter`.
- **D-04:** While writing RBAC filter tests, also implement the missing interceptor/annotation mechanism that sets the required permission so the filter is actually functional and testable.

### Filter Chain Architecture for Phase 2
- **D-05:** Introduce `SecurityFilterChain` bean(s) in Phase 1. Do not defer to Phase 2.
- **D-06:** Register existing custom filters (`JwtAuthFilter`, `ApiKeyAuthFilter`, `RbacAuthorizationFilter`) inside the `SecurityFilterChain` via `addFilterBefore` / `addFilterAfter`. Do not migrate JWT to Spring Security resource server or `AuthenticationProvider` pattern.
- **D-07:** Layer access control: `authorizeHttpRequests` in `SecurityFilterChain` handles public path bypass (replacing `PUBLIC_PATHS` logic in `JwtAuthFilter`). `@PreAuthorize` stays on admin controllers. Custom `RbacAuthorizationFilter` handles fine-grained permission checks (`runs:read`, `spans:read`, etc.) once the interceptor is fixed.
- **D-08:** Multiple `SecurityFilterChain` beans by path: one for `/api/*` (JWT + RBAC), one for `/v1/*` (API key for OTLP ingestion). Use `@Order` to distinguish.

### RBAC Duality: @PreAuthorize vs Custom Filter
- **D-09:** Create a custom `@RequirePermission(String value)` annotation. A `HandlerInterceptor` reads the annotation from the handler method and sets a `requiredPermission` request attribute.
- **D-10:** Migrate `RbacAuthorizationFilter` from reading `X-Required-Permission` **header** to reading the `requiredPermission` **request attribute**. Prevents client spoofing.
- **D-11:** `@PreAuthorize("hasAuthority('admin')")` stays on admin endpoints for defense-in-depth. `@RequirePermission` is added to fine-grained endpoints. Both checks run.
- **D-12:** `@RequirePermission` takes a single permission string only. No array or AND/OR logic.

### Claude's Discretion
- None — all decisions were user-selected.

</decisions>

<canonical_refs>
## Canonical References

**Downstream agents MUST read these before planning or implementing.**

### Roadmap & Requirements
- `.planning/ROADMAP.md` §Phase 1: Security Foundation — goal, depends on, requirements, success criteria
- `.planning/REQUIREMENTS.md` §Phase 0 — Security Migration Prerequisite (SEC-01, SEC-02)
- `.planning/PROJECT.md` §Key Decisions — SAML via Spring Security SAML2, SSO must coexist with existing JWT auth

### Security Implementation Files
- `chorus-observe-server/src/main/java/com/chorus/observe/config/ChorusObserveAutoConfiguration.java` — filter registrations (`FilterRegistrationBean`), `@EnableMethodSecurity`, `PasswordEncoder` bean, `JwtTokenService` bean
- `chorus-observe-server/src/main/java/com/chorus/observe/security/JwtAuthFilter.java` — JWT extraction and validation filter
- `chorus-observe-server/src/main/java/com/chorus/observe/security/RbacAuthorizationFilter.java` — RBAC enforcement filter (to be migrated from header to request attribute)
- `chorus-observe-server/src/main/java/com/chorus/observe/security/ApiKeyAuthFilter.java` — API key validation filter
- `chorus-observe-server/src/main/java/com/chorus/observe/security/JwtTokenService.java` — JWT generation and parsing (jjwt 0.12.6, HS256)
- `chorus-observe-server/src/main/java/com/chorus/observe/security/Permission.java` — permission constants (`runs:read`, `admin`, etc.)
- `chorus-observe-server/src/main/java/com/chorus/observe/service/AuthenticationService.java` — login and API key creation

### Admin Controllers with @PreAuthorize
- `chorus-observe-server/src/main/java/com/chorus/observe/api/UserController.java` — `@PreAuthorize("hasAuthority('admin')")`
- `chorus-observe-server/src/main/java/com/chorus/observe/api/SqlQueryController.java` — `@PreAuthorize("hasAuthority('admin')")`
- `chorus-observe-server/src/main/java/com/chorus/observe/api/RoleController.java` — `@PreAuthorize("hasAuthority('admin')")`
- `chorus-observe-server/src/main/java/com/chorus/observe/api/RetentionPolicyController.java` — `@PreAuthorize("hasAuthority('admin')")`
- `chorus-observe-server/src/main/java/com/chorus/observe/api/TimeTravelController.java` — `@PreAuthorize("hasAuthority('admin')")`
- `chorus-observe-server/src/main/java/com/chorus/observe/api/RedTeamController.java` — `@PreAuthorize("hasAuthority('admin')")`
- `chorus-observe-server/src/main/java/com/chorus/observe/api/ExportController.java` — `@PreAuthorize("hasAuthority('admin')")`

### Build Configuration
- `chorus-observe-server/build.gradle.kts` — Spring Security dependencies (pins already removed in hotfix)

### Test Patterns
- `chorus-observe-server/src/test/java/com/chorus/observe/persistence/RunRepositoryTest.java` — hand-written `InMemoryRunRepository` fake pattern
- `chorus-observe-server/src/test/java/com/chorus/observe/service/OtlpIngestionServiceTest.java` — hand-written in-memory fakes + plain JUnit 5

</canonical_refs>

<code_context>
## Existing Code Insights

### Reusable Assets
- `JwtTokenService` — JWT generation/validation with jjwt 0.12.6. Can be tested directly with a test secret.
- `BCryptPasswordEncoder` — already configured as a `@ConditionalOnMissingBean`.
- `TenantContext` — thread-local context for tenant/user/scopes. Cleared in `finally` blocks by filters.
- In-memory fake repositories (`InMemoryRunRepository`, `InMemoryUserRepository`, etc.) — pattern for test doubles.

### Established Patterns
- **No Mockito**: All tests use hand-written fakes or direct instantiation.
- **FilterRegistrationBean**: Custom filters are registered as beans with `setOrder(N)`. Will migrate to `SecurityFilterChain`.
- **@ConditionalOnMissingBean**: All beans follow this pattern for composability.
- **@EnableMethodSecurity**: Present on auto-configuration class. `@PreAuthorize` is the active method security annotation.

### Integration Points
- `ChorusObserveAutoConfiguration` — central configuration class where `SecurityFilterChain` beans should be added.
- `FilterRegistrationBean` beans for `JwtAuthFilter`, `ApiKeyAuthFilter`, `RbacAuthorizationFilter` — these will be removed/replaced when migrating to `SecurityFilterChain`.
- `WebMvcConfigurer` in `ChorusObserveAutoConfiguration` — CORS and interceptors registered here. The new `HandlerInterceptor` for `@RequirePermission` should be added via `addInterceptors`.
</code_context>

<specifics>
## Specific Ideas

- Spring Security 7.0.0 filter chain order: `ApiKeyAuthFilter` (Order 1) → `TracingFilter` (Order 1) → `RateLimitFilter` (Order 2) → `JwtAuthFilter` (Order 3) → `RbacAuthorizationFilter` (Order 4). Preserve relative ordering in `SecurityFilterChain`.
- Public paths: `/actuator/health`, `/actuator/info`, `/actuator/prometheus`, `/actuator/metrics`, `/v3/api-docs`, `/swagger-ui`, `/swagger-ui.html`, `/webjars/`, `/api/v1/auth/login`, `/api/v1/auth/register`, `/api/v1/auth/forgot-password`, `/api/v1/auth/reset-password`, `/api/v1/auth/verify-email`.
- The `RbacAuthorizationFilter` currently checks `TenantContext.isAuthenticated()` — this should continue working inside `SecurityFilterChain`.
</specifics>

<deferred>
## Deferred Ideas

None — discussion stayed within phase scope.
</deferred>

---

*Phase: 1-Security Foundation*
*Context gathered: 2026-05-23*
