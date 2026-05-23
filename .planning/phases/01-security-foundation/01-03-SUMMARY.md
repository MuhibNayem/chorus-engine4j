# Plan 01-03 Summary: Security Component Unit Tests

**Status:** Complete
**Committed:** bf4f713

## What was built

1. **InMemoryApiKeyRepository** — hand-written fake extending `ApiKeyRepository` with in-memory `HashMap` storage. Supports `save`, `findByKeyHash`, `findByTenant`, `updateLastUsed`, `revoke`.

2. **JwtTokenServiceTest** (5 tests):
   - `shouldGenerateNonNullToken`
   - `shouldParseValidToken` — verifies userId, tenantId, scopes, expiry
   - `shouldReturnNullForExpiredToken`
   - `shouldReturnNullForInvalidSignature`
   - `shouldReturnNullForMalformedToken`

3. **JwtAuthFilterTest** (5 tests):
   - `shouldBypassPublicPathWithoutAuth`
   - `shouldContinueChainWhenTokenMissing`
   - `shouldSetSecurityContextForValidToken` — captures auth during chain execution
   - `shouldContinueChainForInvalidToken`
   - `shouldClearSecurityContextAfterRequest`

4. **ApiKeyAuthFilterTest** (5 tests):
   - `shouldBypassPublicPathWithoutAuth`
   - `shouldAuthenticateWithValidApiKey` — captures auth during chain execution
   - `shouldRejectRevokedApiKey`
   - `shouldContinueChainWhenApiKeyMissing`
   - `shouldClearSecurityContextAfterRequest`

5. **PasswordEncoderTest** (3 tests):
   - `shouldEncodePassword`
   - `shouldMatchCorrectPassword`
   - `shouldNotMatchIncorrectPassword`

## Verification

- `./gradlew test` passes: 18 tests, 0 failures

## Key files created

- `chorus-observe-server/src/test/java/com/chorus/observe/persistence/InMemoryApiKeyRepository.java`
- `chorus-observe-server/src/test/java/com/chorus/observe/security/JwtTokenServiceTest.java`
- `chorus-observe-server/src/test/java/com/chorus/observe/security/JwtAuthFilterTest.java`
- `chorus-observe-server/src/test/java/com/chorus/observe/security/ApiKeyAuthFilterTest.java`
- `chorus-observe-server/src/test/java/com/chorus/observe/security/PasswordEncoderTest.java`

## Notable deviations

- Fixed `InMemoryApiKeyRepository` to pass `new ObjectMapper()` instead of `null` to `super()` — the parent `ApiKeyRepository` requires non-null mapper.
- Fixed `JwtAuthFilterTest` and `ApiKeyAuthFilterTest` to capture `SecurityContextHolder` authentication **during** chain execution (via custom `FilterChain`) instead of after — the `finally` block clears the context after `doFilter` returns.
