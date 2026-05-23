# Phase 2: Enterprise Authentication — Plan Summary

**Planned:** 2026-05-23
**Plans:** 5
**Waves:** 5
**Requirements:** 17 (AUTH-01–AUTH-06, SAML-01–SAML-04, SCIM-01–SCIM-07)

---

## Plans

| Plan | Wave | Depends On | Requirements | Objective |
|------|------|------------|-------------|-----------|
| **02-01** | 1 | — | Infrastructure | Flyway V7 migration, domain models (TenantOauthConfig, TenantSamlConfig, ScimToken), updated User with auth_source, pure JDBC repositories |
| **02-02** | 2 | 02-01 | AUTH-01–AUTH-06 | OAuth2/OIDC SSO: ClientRegistrationRepository from DB, oauth2Login() in SecurityFilterChain, JIT provisioning, admin CRUD API |
| **02-03** | 3 | 02-01, 02-02 | SAML-01–SAML-04 | SAML 2.0: RelyingPartyRegistrationRepository from DB, metadata resolver with cert thumbprint lookup, saml2Login(), assertion replay cache (2-min TTL) |
| **02-04** | 4 | 02-01 | SCIM-01–SCIM-07 | SCIM v2: ScimTokenAuthFilter, Users CRUD endpoints, filter queries, ServiceProviderConfig, soft-delete, duplicate 409 |
| **02-05** | 5 | 02-02, 02-03, 02-04 | All | Integration tests: JIT provisioning, SCIM token auth, SCIM CRUD, assertion replay cache. Hand-written fakes, no Mockito. |

## Key Decisions Locked (from 02-CONTEXT.md)

- **Separate tables** for OAuth2 (`tenant_oauth_configs`) and SAML (`tenant_saml_configs`) configs — multiple IdPs per tenant
- **Curated OAuth2 schema** (provider_name, client_id, client_secret, issuer_uri, scopes, default_role, enabled)
- **SAML cert thumbprint + metadata lookup** — no full PEM in DB
- **Link SSO to same user row** when email matches; preserve existing roles
- **JIT users are SSO-only** (null/empty passwordHash)
- **`LOWER(email)` + `tenant_id` unique index** for duplicate prevention
- **Separate `scim_tokens` table** with scoped tokens (`scim:users:read`, `scim:users:write`)
- **Dedicated `ScimTokenAuthFilter`** for `/scim/**` paths
- **Configurable `default_role` per IdP** — applies to both SSO JIT and SCIM creation
- **Require local admin** before enabling SSO config

## Files Created

- `02-CONTEXT.md` — Phase decisions and codebase context
- `02-DISCUSSION-LOG.md` — Audit trail of gray area discussions
- `PLAN-02-01.md` through `PLAN-02-05.md` — Executable implementation plans
- `02-SUMMARY.md` — This file

## Next Step

Execute plans in wave order:
```
/gsd-execute-phase 2
```
