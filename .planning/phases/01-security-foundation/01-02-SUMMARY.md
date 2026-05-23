# Plan 01-02 Summary: @RequirePermission Annotation and PermissionInterceptor

**Status:** Complete
**Committed:** ba79418

## What was built

1. **Created `@RequirePermission` annotation** — runtime-retained, method-targeted annotation that takes a single permission string (e.g., `"runs:read"`).

2. **Created `PermissionInterceptor`** — implements `HandlerInterceptor` and:
   - Reads `@RequirePermission` from the handler method in `preHandle()`
   - Returns `true` (pass-through) when no annotation is present
   - Returns 401 when `TenantContext.isAuthenticated()` is false
   - Checks `scopes` request attribute against the required permission OR `admin`
   - Returns 403 when the user lacks the required permission

3. **Registered `PermissionInterceptor`** in `ChorusObserveAutoConfiguration`:
   - Added `@Bean` method for `PermissionInterceptor`
   - Injected it into `chorusObserveWebMvcConfigurer`
   - Registered with `addPathPatterns("/api/**", "/v1/**")` as the first interceptor

4. **Deleted `RbacAuthorizationFilter`** — the broken filter that read a non-existent `X-Required-Permission` header.

## Verification

- `./gradlew compileJava` passes
- No references to `RbacAuthorizationFilter` remain in the codebase

## Key files modified

- `chorus-observe-server/src/main/java/com/chorus/observe/security/RequirePermission.java` (new)
- `chorus-observe-server/src/main/java/com/chorus/observe/security/PermissionInterceptor.java` (new)
- `chorus-observe-server/src/main/java/com/chorus/observe/config/ChorusObserveAutoConfiguration.java`
- `chorus-observe-server/src/main/java/com/chorus/observe/security/RbacAuthorizationFilter.java` (deleted)
