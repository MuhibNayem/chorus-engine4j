# Phase 1: Security Foundation — Research Findings

**Researched:** 2026-05-23
**Scope:** Spring Security 7.0.0 migration, custom filter integration, testing strategy, and RBAC architecture for chorus-observe-server

---

## Spring Security 7 Breaking Changes

### Confirmed: Already on 7.0.0
Running `./gradlew dependencies` shows `spring-security-core:7.0.0` (and web/config/crypto) already resolved by the Spring Boot 4.0.0 BOM. SEC-01 is effectively complete.

### Breaking Changes Affecting This Codebase

| Removed in 7.0 | Current Code Impact | Required Action |
|---|---|---|
| `and()` in HttpSecurity DSL | None — we have no `SecurityFilterChain` yet | Use lambda-only DSL when writing new chains |
| `authorizeRequests()` | None — we use `authorizeHttpRequests` already | None |
| `AntPathRequestMatcher` / `MvcRequestMatcher` | None | Use `requestMatchers()` inside `authorizeHttpRequests` |
| `AuthorizationManager#check` | None — internal API | None |
| `AccessDecisionManager` / `AccessDecisionVoter` | None — moved to `spring-security-access` module | None for our use case |

### `@EnableMethodSecurity` — No Changes
- Already present on `ChorusObserveAutoConfiguration`. In Spring Security 7, `@EnableMethodSecurity` continues to enable `@PreAuthorize`, `@PostAuthorize`, `@PreFilter`, `@PostFilter` by default.
- `@PreAuthorize("hasAuthority('admin')")` expression syntax is unchanged.
- **CVE-2025-41248/41249 fix:** Spring Security 7.0.x resolves annotation-detection bypass vulnerabilities affecting 6.4.x/6.5.x with generic superclass methods. Our controllers are concrete classes with direct annotations — not affected.

### `BCryptPasswordEncoder` — No API Changes
- Constructor signatures identical: `BCryptPasswordEncoder()`, `BCryptPasswordEncoder(int strength)`, `BCryptPasswordEncoder(BCryptVersion version, int strength, SecureRandom random)`.
- Default strength remains 10, default version remains `$2a`.
- No migration needed for existing `PasswordEncoder` bean.

---

## Multiple SecurityFilterChain Configuration

### Pattern
Spring Security 7 supports multiple `SecurityFilterChain` beans distinguished by `@Order` and `securityMatcher()`:

```java
@Bean
@Order(1)
public SecurityFilterChain apiSecurityFilterChain(HttpSecurity http) throws Exception {
    http
        .securityMatcher("/api/**", "/v1/**")
        .csrf(csrf -> csrf.disable())
        .authorizeHttpRequests(auth -> auth
            .requestMatchers(PUBLIC_PATHS_ARRAY).permitAll()
            .anyRequest().authenticated()
        )
        .addFilterBefore(jwtAuthFilter, UsernamePasswordAuthenticationFilter.class)
        .addFilterBefore(apiKeyAuthFilter, UsernamePasswordAuthenticationFilter.class)
        .addFilterAfter(rbacAuthorizationFilter, UsernamePasswordAuthenticationFilter.class);
    return http.build();
}

@Bean
@Order(2)
public SecurityFilterChain actuatorSecurityFilterChain(HttpSecurity http) throws Exception {
    http
        .securityMatcher("/actuator/**")
        .authorizeHttpRequests(auth -> auth.anyRequest().permitAll());
    return http.build();
}
```

### Key Rules
- **Lower `@Order` = higher priority.** First matching chain wins; remaining chains are skipped.
- **`securityMatcher()`** defines which requests a chain handles. If a request matches no chain, it receives **no Spring Security protection**.
- A catch-all chain (no `securityMatcher`) with no `@Order` acts as the default fallback.

### Recommendation for Chorus Observe
Instead of two chains (`/api/*` and `/v1/*`), use **one chain** with `securityMatcher("/api/**", "/v1/**", "/actuator/**")` because:
- Both `/api/*` and `/v1/*` share the same public paths and auth mechanisms.
- The `ApiKeyAuthFilter` already handles path differentiation internally.
- A single chain avoids accidental gaps where a path falls between matchers.
- If split chains are still desired, order them: API chain `@Order(1)`, fallback `@Order(2)`.

---

## Filter Ordering in Security 7

### Standard Filter Order (relevant subset)
1. `SecurityContextHolderFilter` (was `SecurityContextPersistenceFilter`)
2. `HeaderWriterFilter`
3. `CsrfFilter`
4. `LogoutFilter`
5. `UsernamePasswordAuthenticationFilter`
6. `BasicAuthenticationFilter`
7. `RequestCacheAwareFilter`
8. `SecurityContextHolderAwareRequestFilter`
9. `AnonymousAuthenticationFilter`
10. `ExceptionTranslationFilter`
11. `AuthorizationFilter` (replaced `FilterSecurityInterceptor` in 6.x)

### Placement for Custom Filters

| Filter | Placement | Rationale |
|---|---|---|
| `JwtAuthFilter` | `addFilterBefore(jwtAuthFilter, UsernamePasswordAuthenticationFilter.class)` | Extract JWT and populate `SecurityContextHolder` before standard auth filters. Must run before `AnonymousAuthenticationFilter` so method security sees an authenticated principal. |
| `ApiKeyAuthFilter` | `addFilterBefore(apiKeyAuthFilter, UsernamePasswordAuthenticationFilter.class)` | Same rationale. Can share the same position relative to `UsernamePasswordAuthenticationFilter`; Spring resolves both. |
| `RbacAuthorizationFilter` | `addFilterAfter(rbacAuthFilter, JwtAuthFilter.class)` or `addFilterBefore(rbacAuthFilter, AuthorizationFilter.class)` | Must run after auth context is established but before the final `AuthorizationFilter` (which handles `authorizeHttpRequests`). |

### Critical Finding: `SecurityContextHolder` Must Be Populated
**Current `JwtAuthFilter` and `ApiKeyAuthFilter` do NOT populate `SecurityContextHolder`.** They only set `TenantContext` and a request attribute `"scopes"`.

`@PreAuthorize` uses Spring AOP method interceptors that read `SecurityContextHolder.getContext().getAuthentication()`. If the context is empty, `@PreAuthorize("hasAuthority('admin')")` will **deny all access** (or fall through to anonymous, which lacks `admin`).

**Required fix for Phase 1:** Both filters must create a `UsernamePasswordAuthenticationToken` (or custom `Authentication` implementation) and call `SecurityContextHolder.getContext().setAuthentication(authToken)` before `chain.doFilter()`. The token must include the user's scopes as `SimpleGrantedAuthority` objects so `hasAuthority('admin')` evaluates correctly.

---

## Testing Without Mockito/SpringBootTest

### Existing Project Pattern
- Plain JUnit 5 + AssertJ.
- Hand-written in-memory fakes (e.g., `InMemoryRunRepository`).
- No `@SpringBootTest`, no `MockMvc`, no Mockito.

### Test Strategy for Security Components

#### 1. `JwtTokenService` — Straightforward Unit Tests
- Direct instantiation with a test secret.
- Assert: token generation, parsing, expiry rejection, invalid signature rejection.
- No Spring context needed.

#### 2. Custom Filters (`JwtAuthFilter`, `ApiKeyAuthFilter`, `RbacAuthorizationFilter`)
- Instantiate filter directly.
- Use `org.springframework.mock.web.MockHttpServletRequest` and `MockHttpServletResponse` (available via `spring-test` on classpath through `spring-boot-starter-test`).
- Use `org.springframework.mock.web.MockFilterChain` or a custom `FilterChain` lambda to capture `doFilter` calls.
- Example:
  ```java
  var req = new MockHttpServletRequest();
  var res = new MockHttpServletResponse();
  var chain = new MockFilterChain();
  req.addHeader("Authorization", "Bearer " + validToken);
  filter.doFilter(req, res, chain);
  assertThat(chain.getRequest()).isNotNull(); // chain continued
  ```
- **No Mockito needed.** For `ApiKeyAuthFilter`, create an `InMemoryApiKeyRepository` fake that implements `ApiKeyRepository`.

#### 3. `@PreAuthorize` Method Security
Two valid approaches without `@SpringBootTest`:

**Option A: Lightweight Spring Test Context**
- Create a minimal `@Configuration` class with `@EnableMethodSecurity` and a test controller bean.
- Use `SpringRunner` / `@ExtendWith(SpringExtension.class)` with manually registered beans.
- This is acceptable but introduces some Spring wiring.

**Option B: Manual `AuthorizationManager` Invocation (Pure Unit Test)**
- `@PreAuthorize` is evaluated by `PreAuthorizeAuthorizationManager`.
- Build a `MethodInvocation` (using `org.springframework.aop.framework.ReflectiveMethodInvocation` or a simple proxy), set `SecurityContextHolder`, and invoke `AuthorizationManager#authorize`.
- More complex; only justified if Option A is too heavy.

**Option C: Test via Controller with Embedded Jetty/Tomcat (Not Recommended)**
- Violates the "no `@SpringBootTest`" rule.

**Recommended:** Option A with a `@WebMvcTest`-like lightweight context is the pragmatic middle ground, but since the project avoids `@SpringBootTest`, use a focused `@ContextConfiguration` test that loads only the security config + one controller. **Alternatively**, simply test that `SecurityContextHolder` contains the correct authorities after the filter runs — this indirectly verifies `@PreAuthorize` will work.

#### 4. `BCryptPasswordEncoder`
- Direct unit test: `new BCryptPasswordEncoder().encode("password")` and `.matches(...)`.
- Assert encoded hash is non-null and matches succeed.

#### 5. `spring-security-test` Dependency
Currently **NOT** on classpath. It provides `SecurityMockMvcRequestPostProcessors` and `@WithMockUser`. Since we are avoiding MockMvc and Mockito, **do not add it** — it adds little value for our chosen testing style.

---

## jjwt 0.12.6 Compatibility

- **jjwt is independent of Spring Security.** It handles JWT parsing/generation only.
- Spring Security 7 has no breaking changes that affect jjwt.
- The current `JwtTokenService` uses jjwt 0.12.6 API correctly:
  - `Jwts.builder()` chain
  - `.signWith(key)` (HS256)
  - `Jwts.parser().verifyWith(key).build().parseSignedClaims(token)`
- **No migration needed.**
- Spring Security 7 method security does NOT require OAuth2 Resource Server or `NimbusJwtDecoder`. Custom auth via manual `SecurityContextHolder` population works perfectly with `@PreAuthorize`.

---

## HandlerInterceptor vs Filter Ordering (Critical Finding)

### The Problem
User decision D-09/D-10 proposes:
1. A `HandlerInterceptor` reads `@RequirePermission` from the handler method and sets a `requiredPermission` request attribute.
2. `RbacAuthorizationFilter` reads this attribute from the request and enforces it.

### Execution Order in Spring
```
Client → Filter Chain (Servlet layer) → DispatcherServlet → HandlerInterceptor.preHandle() → Controller
```

**Filters run BEFORE `HandlerInterceptor.preHandle()`.** Therefore, `RbacAuthorizationFilter` (a filter) will execute **before** the interceptor sets the `requiredPermission` attribute. The filter will always see a null attribute and skip the check.

### Impact
This architecture is **fundamentally broken** as designed. The RBAC filter cannot depend on data set by a later interceptor.

### Solutions

| Approach | Description | Trade-off |
|---|---|---|
| **A. Move RBAC check into HandlerInterceptor** | Replace `RbacAuthorizationFilter` with a `PermissionInterceptor` that reads `@RequirePermission`, checks `TenantContext` scopes, and rejects if unauthorized. | Cleanest. Interceptor has natural access to handler metadata. Loses "filter chain" placement but gains correctness. |
| **B. Use a custom Security filter that introspects handler** | A filter could use `HandlerMapping` to resolve the handler early, but this is complex and couples servlet layer to MVC layer. | Complex, fragile. |
| **C. Use `@PreAuthorize` with a custom bean** | `@PreAuthorize("@permissionChecker.check('runs:read')")` where `permissionChecker` reads `TenantContext`. | Bypasses the annotation/filter duality entirely. Defense-in-depth can still use `@PreAuthorize("hasAuthority('admin')")` on admin methods. |
| **D. Set attribute in filter itself** | Have the filter scan for `@RequirePermission` on the target controller method using reflection. | Very complex; filters don't know the handler method at their execution point. |

### Recommendation
**Adopt Approach A:**
- Create `PermissionInterceptor implements HandlerInterceptor`.
- In `preHandle()`, use `handler` parameter (cast to `HandlerMethod`) to read `@RequirePermission` annotation.
- Check `TenantContext.isAuthenticated()` and scopes. If missing permission, send 403 and return `false`.
- Register via `WebMvcConfigurer.addInterceptors()`.
- **Remove `RbacAuthorizationFilter` entirely** — the interceptor replaces it.
- Keep `@PreAuthorize("hasAuthority('admin')")` on admin controllers for defense-in-depth.

This satisfies D-11 (defense-in-depth) and D-12 (single permission string) while fixing the ordering bug.

---

## Recommended Implementation Approach

### 1. Build Single SecurityFilterChain Bean
In `ChorusObserveAutoConfiguration` (or a new `@Configuration` class):

```java
@Bean
@ConditionalOnMissingBean
public SecurityFilterChain chorusObserveSecurityFilterChain(
        HttpSecurity http,
        JwtTokenService jwtTokenService,
        ApiKeyRepository apiKeyRepository,
        ChorusObserveProperties properties) throws Exception {

    JwtAuthFilter jwtAuthFilter = new JwtAuthFilter(jwtTokenService, true);
    ApiKeyAuthFilter apiKeyAuthFilter = new ApiKeyAuthFilter(apiKeyRepository, properties.getSecurity().isApiKeyEnabled());
    RbacAuthorizationFilter rbacFilter = new RbacAuthorizationFilter(true);

    http
        .securityMatcher("/api/**", "/v1/**")
        .csrf(csrf -> csrf.disable())
        .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
        .authorizeHttpRequests(auth -> auth
            .requestMatchers(PUBLIC_PATHS).permitAll()
            .anyRequest().authenticated()
        )
        .addFilterBefore(jwtAuthFilter, UsernamePasswordAuthenticationFilter.class)
        .addFilterBefore(apiKeyAuthFilter, UsernamePasswordAuthenticationFilter.class)
        .addFilterAfter(rbacFilter, UsernamePasswordAuthenticationFilter.class);

    return http.build();
}
```

### 2. Fix Filters to Populate `SecurityContextHolder`
- `JwtAuthFilter`: After validating JWT, create `UsernamePasswordAuthenticationToken(userId, null, authorities)` and set it in `SecurityContextHolder`.
- `ApiKeyAuthFilter`: Same pattern with API key principal.
- Both must clear `SecurityContextHolder` in a `finally` block (in addition to `TenantContext.clear()`).

### 3. Remove FilterRegistrationBeans
Delete `FilterRegistrationBean` beans for `JwtAuthFilter`, `ApiKeyAuthFilter`, and `RbacAuthorizationFilter`. Registering the same filter twice (once as `FilterRegistrationBean`, once in `SecurityFilterChain`) causes double execution.

### 4. Replace RbacAuthorizationFilter with PermissionInterceptor
- Create `@RequirePermission(String value)` annotation.
- Create `PermissionInterceptor implements HandlerInterceptor`.
- In `preHandle(HttpServletRequest, HttpServletResponse, Object handler)`:
  - If `handler` is `HandlerMethod`, check for `@RequirePermission`.
  - If present, verify `TenantContext.isAuthenticated()` and scopes contain the required permission or `admin`.
  - If denied, send 403 and return `false`.
- Register via `WebMvcConfigurer.addInterceptors()`.
- Remove `RbacAuthorizationFilter` class or deprecate it.

### 5. Write Tests
- `JwtTokenServiceTest`: Generate, parse, expiry, bad signature.
- `JwtAuthFilterTest`: Public path bypass, missing token (continues chain but no auth), valid token sets `SecurityContextHolder`.
- `ApiKeyAuthFilterTest`: Valid key, revoked key, missing key.
- `PermissionInterceptorTest`: Annotated method with valid scope, annotated method missing scope, no annotation passes through.
- `MethodSecurityTest`: Controller method with `@PreAuthorize("hasAuthority('admin')")` — verify allowed with admin authority, denied without.
- `PasswordEncoderTest`: Encode + match roundtrip.

### 6. Verify Dependency Report
Run `./gradlew dependencies --configuration runtimeClasspath | grep spring-security` and confirm only 7.0.x versions.

---

## Risks and Mitigations

| Risk | Likelihood | Impact | Mitigation |
|---|---|---|---|
| `SecurityContextHolder` population forgotten | Medium | High | `@PreAuthorize` silently denies all admin requests. Add explicit test for method security with authenticated context. |
| Filters registered twice (bean + `SecurityFilterChain`) | Medium | High | Double auth processing, potential bugs. Remove `FilterRegistrationBean` definitions when adding `SecurityFilterChain`. |
| HandlerInterceptor ordering misunderstanding | High (already present in design) | High | RBAC bypass. Replace filter-based RBAC with interceptor-based RBAC as recommended above. |
| Public paths accidentally protected | Low | Medium | Login/swagger/actuator blocked. Maintain explicit `PUBLIC_PATHS` set in `SecurityFilterChain` and test each path returns 200 without auth. |
| `TenantContext` not cleared after `SecurityFilterChain` migration | Low | Medium | Thread-local leak. Ensure `try/finally` in filters still calls `TenantContext.clear()` AND `SecurityContextHolder.clearContext()`. |
| Multiple `SecurityFilterChain` gap paths | Low | High | If using multiple chains, unmatched paths get zero security. Use a single chain with broad `securityMatcher` plus explicit rules. |
| jjwt 0.12.6 + Security 7 classpath conflict | None | None | Already verified: jjwt is self-contained. No interaction with Spring Security internals. |

---

## RESEARCH COMPLETE
