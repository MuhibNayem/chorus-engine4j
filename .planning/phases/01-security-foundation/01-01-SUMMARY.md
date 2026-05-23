# Plan 01-01 Summary: SecurityFilterChain and SecurityContextHolder Fix

**Status:** Complete
**Committed:** 456e915

## What was built

1. **Removed FilterRegistrationBean beans** for `JwtAuthFilter`, `ApiKeyAuthFilter`, and `RbacAuthorizationFilter` from `ChorusObserveAutoConfiguration` to prevent double filter execution.

2. **Added `SecurityFilterChain` bean** (`chorusObserveSecurityFilterChain`) with:
   - `securityMatcher("/api/**", "/v1/**", "/actuator/**")`
   - CSRF disabled, stateless sessions
   - Public paths permitAll (actuator health, swagger, auth endpoints)
   - All other requests require authentication
   - Both `JwtAuthFilter` and `ApiKeyAuthFilter` registered via `addFilterBefore`

3. **Fixed `JwtAuthFilter`** to populate `SecurityContextHolder` with a `UsernamePasswordAuthenticationToken` containing `SimpleGrantedAuthority` objects derived from JWT scopes. Clears `SecurityContextHolder` in `finally` block.

4. **Fixed `ApiKeyAuthFilter`** to populate `SecurityContextHolder` with a `UsernamePasswordAuthenticationToken` containing `SimpleGrantedAuthority` objects derived from API key scopes. Clears `SecurityContextHolder` in `finally` block.

## Verification

- `spring-security-core:7.0.0` confirmed on runtime classpath
- `./gradlew compileJava` passes with zero errors

## Key files modified

- `chorus-observe-server/src/main/java/com/chorus/observe/config/ChorusObserveAutoConfiguration.java`
- `chorus-observe-server/src/main/java/com/chorus/observe/security/JwtAuthFilter.java`
- `chorus-observe-server/src/main/java/com/chorus/observe/config/ApiKeyAuthFilter.java`

## Notable deviations

- Kept `FilterRegistrationBean` beans for `TracingFilter` and `RateLimitFilter` (non-auth filters)
- Did NOT remove `RbacAuthorizationFilter` class itself — that is handled in Plan 01-02
