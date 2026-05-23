# Plan 01-04 Summary: RBAC and Method Security Integration Tests

**Status:** Complete
**Committed:** 79d81b1

## What was built

1. **PermissionInterceptorTest** (6 tests):
   - `shouldAllowWhenScopeMatches` — allowed when user has exact required permission
   - `shouldAllowWhenAdminScopePresent` — admin overrides all permissions
   - `shouldDenyWhenScopeMissing` — 403 when scope doesn't match and no admin
   - `shouldDenyWhenNotAuthenticated` — 401 when TenantContext shows no auth
   - `shouldAllowWhenNoAnnotation` — pass-through for methods without `@RequirePermission`
   - `shouldAllowNonHandlerMethod` — pass-through for non-handler targets

2. **MethodSecurityTest** (4 tests):
   - `shouldAllowAdminMethodWithAdminAuthority` — `@PreAuthorize` allows admin
   - `shouldDenyAdminMethodWithoutAdminAuthority` — `@PreAuthorize` denies viewer on admin endpoint
   - `shouldDenyAdminMethodWhenAnonymous` — `@PreAuthorize` rejects anonymous (AuthenticationCredentialsNotFoundException)
   - `shouldAllowUserMethodWithViewerAuthority` — `@PreAuthorize` allows matching authority

## Verification

- `./gradlew test` passes: all tests green

## Key files created

- `chorus-observe-server/src/test/java/com/chorus/observe/security/PermissionInterceptorTest.java`
- `chorus-observe-server/src/test/java/com/chorus/observe/security/MethodSecurityTest.java`

## Notable deviations

- Fixed `shouldDenyAdminMethodWhenAnonymous` to expect `AuthenticationCredentialsNotFoundException` (not `AccessDeniedException`) — Spring Security throws this when no Authentication exists in SecurityContext for `@PreAuthorize` evaluation.
