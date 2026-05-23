# Phase 2: Enterprise Authentication - Context

**Gathered:** 2026-05-23
**Status:** Ready for planning

<domain>
## Phase Boundary

Enterprise users can authenticate via OAuth2/OIDC SSO or SAML 2.0, and identity providers can provision and manage users through the SCIM v2 API, all sharing a single Flyway migration (V7). SSO must coexist with the existing JWT email/password auth from Phase 1.

**Requirements:** AUTH-01, AUTH-02, AUTH-03, AUTH-04, AUTH-05, AUTH-06, SAML-01, SAML-02, SAML-03, SAML-04, SCIM-01, SCIM-02, SCIM-03, SCIM-04, SCIM-05, SCIM-06, SCIM-07

**Success criteria (from ROADMAP.md):**
1. User can click "Sign in with Google" or "Sign in with GitHub" on the login page and reach the Chorus dashboard (JIT-provisioned with VIEWER role on first login, existing RBAC filter unmodified)
2. User can complete an SP-initiated SAML login flow against a real Okta or Azure AD test tenant, including replay-attack rejection on assertion reuse within 2 minutes
3. An identity provider can POST to `/scim/v2/Users` with a bearer token, and a subsequent GET returns the created user; a SCIM DELETE soft-deactivates the user (row preserved with `active=false`)
4. An admin can configure an OAuth2/OIDC IdP per tenant through the settings page, and subsequent logins use that configuration without server restart
5. Attempting to create a SCIM user with the same email as an existing user (including case variants) returns a 409 Conflict, not a duplicate row
</domain>

<decisions>
## Implementation Decisions

### IdP Configuration Storage
- **D-01:** Separate tables for OAuth2 and SAML configs: `tenant_oauth_configs` and `tenant_saml_configs`. Do not reuse `tenants.config` JSONB field.
- **D-02:** Multiple IdPs per tenant supported. Composite key: `(id, tenant_id)`. A tenant can have multiple OAuth2 configs (Google + Azure AD) and multiple SAML configs.
- **D-03:** Curated subset of Spring Security client registration schema for OAuth2: `provider_name`, `client_id`, `client_secret`, `issuer_uri`, `scopes`, `enabled`, `default_role`. Does not include full Spring schema (authorization_grant_type, redirect_uri, etc. — use sensible defaults).
- **D-04:** SAML configs store only the X.509 certificate SHA-256 thumbprint. The full certificate is fetched from the IdP's metadata URL at runtime. Auto-rotates when IdP updates certs.

### SSO-to-Local User Linking
- **D-05:** When an SSO user logs in with an email matching an existing local account, link to the **same user row**. Do not create a separate SSO-only user row.
- **D-06:** Track auth source per session. Add `auth_source` to the user model or JWT claims: `LOCAL`, `OAUTH2`, `SAML`. Used for audit logging.
- **D-07:** JIT-provisioned SSO users are SSO-only — they have a null `passwordHash` and cannot use email/password login. No password setup flow for JIT users.
- **D-08:** One email = one user per tenant. Enforce via `LOWER(email)` + `tenant_id` unique index on the `users` table. This satisfies SCIM-07 duplicate prevention.

### SCIM Token Model
- **D-09:** Separate `scim_tokens` table. Do not reuse `api_keys`. Fields: `token_hash`, `tenant_id`, `name`, `scopes`, `created_at`, `expires_at`, `revoked_at`. No `user_id` — SCIM acts on behalf of the IdP/tenant, not a user.
- **D-10:** SCIM tokens are scoped: `scim:users:read`, `scim:users:write`, etc. Matches existing API key scope pattern.
- **D-11:** Admin creates SCIM tokens via API/settings page. Raw token returned **once** (like AWS access keys) and never shown again. Admin copies it into their IdP (Okta, Azure AD).
- **D-12:** Dedicated `ScimTokenAuthFilter` for `/scim/**` paths. Do not extend `ApiKeyAuthFilter`. Validates scim_tokens table, sets `TenantContext` and `SecurityContextHolder`.

### JIT Role Assignment
- **D-13:** Configurable `default_role` per IdP config (OAuth2 and SAML tables both get this column). Admin sets VIEWER, ADMIN, etc. at config time. Not hardcoded.
- **D-14:** IdP `default_role` applies to both SSO JIT provisioning and SCIM user creation. One source of truth per IdP.
- **D-15:** Before saving an SSO IdP config, verify the tenant has at least one user with ADMIN role who can log in via email/password. Prevents lockout if SSO breaks.
- **D-16:** When an existing local user links to SSO (same email), preserve their existing roles. Do not override with the IdP default_role. Default_role only applies to brand-new JIT users.

### Claude's Discretion
- None — all decisions were user-selected.

</decisions>

<canonical_refs>
## Canonical References

**Downstream agents MUST read these before planning or implementing.**

### Roadmap & Requirements
- `.planning/ROADMAP.md` §Phase 2: Enterprise Authentication — goal, depends on, requirements, success criteria
- `.planning/REQUIREMENTS.md` §Authentication — OAuth2/OIDC SSO (AUTH-01–AUTH-06), SAML 2.0 (SAML-01–SAML-04), SCIM v2 (SCIM-01–SCIM-07)
- `.planning/PROJECT.md` §Key Decisions — "SAML via Spring Security SAML2", "SSO must coexist with existing JWT email/password auth"

### Phase 1 Context (blocking prerequisite)
- `.planning/phases/01-security-foundation/01-CONTEXT.md` — SecurityFilterChain architecture, filter ordering, @PreAuthorize patterns, PermissionInterceptor

### Security Implementation Files
- `chorus-observe-server/src/main/java/com/chorus/observe/config/ChorusObserveAutoConfiguration.java` — SecurityFilterChain, @EnableMethodSecurity, filter registration
- `chorus-observe-server/src/main/java/com/chorus/observe/security/JwtAuthFilter.java` — JWT extraction, SecurityContextHolder population pattern
- `chorus-observe-server/src/main/java/com/chorus/observe/security/JwtTokenService.java` — JWT generation/validation (jjwt 0.12.6, HS256)
- `chorus-observe-server/src/main/java/com/chorus/observe/security/TenantContext.java` — thread-local tenant/user/scopes context
- `chorus-observe-server/src/main/java/com/chorus/observe/security/PermissionInterceptor.java` — @RequirePermission interceptor pattern
- `chorus-observe-server/src/main/java/com/chorus/observe/config/ApiKeyAuthFilter.java` — API key validation filter pattern

### Domain Models
- `chorus-observe-server/src/main/java/com/chorus/observe/model/User.java` — userId, tenantId, email, passwordHash, displayName, status, lastLoginAt
- `chorus-observe-server/src/main/java/com/chorus/observe/model/Tenant.java` — tenantId, name, config (Map<String,Object>), status
- `chorus-observe-server/src/main/java/com/chorus/observe/model/Role.java` — roleId, tenantId, name, permissions
- `chorus-observe-server/src/main/java/com/chorus/observe/model/UserRole.java` — userId, roleId join
- `chorus-observe-server/src/main/java/com/chorus/observe/model/ApiKey.java` — keyHash, tenantId, userId, scopes

### Persistence & Schema
- `chorus-observe-server/src/main/java/com/chorus/observe/persistence/UserRepository.java` — pure JDBC, RowMapper, JSONB
- `chorus-observe-server/src/main/resources/db/migration/V5__enterprise_rbac_*.sql` — existing users, roles, api_keys schema
- `chorus-observe-server/src/main/java/com/chorus/observe/service/AuthenticationService.java` — login, password hashing, JWT issuance

### Auth Controller
- `chorus-observe-server/src/main/java/com/chorus/observe/api/AuthController.java` — /api/v1/auth/login, /register, /me

### Build Configuration
- `chorus-observe-server/build.gradle.kts` — Spring Security 7.0.0 (via BOM), jjwt 0.12.6

</canonical_refs>

<code_context>
## Existing Code Insights

### Reusable Assets
- `JwtTokenService` — JWT generation/validation. SSO login success handler will call this to issue a Chorus JWT (AUTH-05).
- `TenantContext` — thread-local context. All new filters (OAuth2, SAML, SCIM) must populate and clear it in finally blocks.
- `UserRepository` / `RoleRepository` / `UserRoleRepository` — pure JDBC pattern. New repositories for tenant_oauth_configs, tenant_saml_configs, scim_tokens follow same pattern.
- `BCryptPasswordEncoder` — already a bean. Not needed for SSO users (passwordHash = null).
- `ApiKeyAuthFilter` — pattern for token-based auth filters. `ScimTokenAuthFilter` mirrors this structure.
- `InMemoryApiKeyRepository` / `InMemoryUserRepository` — test fake pattern for new repositories.

### Established Patterns
- **No JPA/Hibernate**: All persistence is pure JDBC with JdbcTemplate and hand-written RowMappers.
- **No Mockito**: All tests use hand-written in-memory fakes or direct instantiation.
- **Records for models**: All domain objects are Java records. New models should follow this.
- **@ConditionalOnMissingBean**: All beans follow this for composability.
- **SecurityFilterChain with securityMatcher**: Single chain matching `/api/**`, `/v1/**`, `/actuator/**`. OAuth2/SAML login endpoints may need additional chains or matcher adjustments.
- **Filter ordering**: Custom filters added before `UsernamePasswordAuthenticationFilter` in the SecurityFilterChain.

### Integration Points
- `ChorusObserveAutoConfiguration` — central config class. New beans (SAML config loader, OAuth2 client registration repository, SCIM token filter) register here.
- `AuthenticationService` — existing login logic. SSO success handlers will call `UserRepository` to find/link users and `JwtTokenService` to issue tokens.
- `UserController` / `RoleController` — gated by `@PreAuthorize("hasAuthority('admin')")`. New endpoints for IdP config management need similar gating.
- Existing Flyway migrations V1–V6. Phase 2 requires V7 for new tables.

</code_context>

<specifics>
## Specific Ideas

- Spring Security 7.0.0 OAuth2 client: dependency is `spring-security-oauth2-client` + `spring-security-oauth2-jose`. SAML: `spring-security-saml2-service-provider` (uses OpenSAML 4 in Spring Security 7).
- OAuth2 login flow: Spring's `oauth2Login()` in SecurityFilterChain triggers the redirect. Custom `AuthenticationSuccessHandler` converts the OAuth2AuthenticationToken into a Chorus JWT and redirects to the frontend with the token.
- SAML login flow: Spring's `saml2Login()` in SecurityFilterChain. Custom `Saml2AuthenticationSuccessHandler` extracts SAML assertion attributes (email, name), JIT-provisions user, issues Chorus JWT.
- SAML replay attack prevention: Use an in-memory/assertion-id cache (e.g., Caffeine or ConcurrentHashMap with TTL) storing seen `InResponseTo` / `AssertionID` values for 2 minutes.
- SCIM v2 endpoints: `/scim/v2/Users`, `/scim/v2/Groups` (Groups out of scope per REQUIREMENTS.md deferred), `/scim/v2/ServiceProviderConfig`.
- SCIM filter parsing: `?filter=userName eq "..."` — implement a simple filter parser or use a lightweight SCIM library. Given the no-external-deps preference of the core module, a hand-written parser for `eq` and `and` may be preferred.
- IdP metadata fetcher: HTTP client (JDK HttpClient, per AGENTS.md) fetches SAML metadata from IdP URL, extracts X.509 cert by thumbprint, caches with TTL.
- JIT provisioning: On OAuth2/SAML success, check `UserRepository.findByEmailAndTenantId`. If found, update `last_login_at` and `auth_source`. If not found, create user with `status=ACTIVE`, `passwordHash=null`, `auth_source=OAUTH2/SAML`, assign default_role from IdP config.
</specifics>

<deferred>
## Deferred Ideas

- SCIM Groups push for role mapping — deferred to v1.1 per REQUIREMENTS.md
- Per-tenant SSO enforcement (block email/password for SSO-only tenants) — v1.1
- IdP-initiated SAML SSO — v1.1
- SAML attribute mapping (IdP claim → Chorus role) — v1.1
- IdP-initiated SAML logout (SLO) — not in requirements, deferred
- OAuth2 refresh tokens / token revocation — not in requirements, Chorus JWT handles session lifecycle
</deferred>

---

*Phase: 2-Enterprise Authentication*
*Context gathered: 2026-05-23*
