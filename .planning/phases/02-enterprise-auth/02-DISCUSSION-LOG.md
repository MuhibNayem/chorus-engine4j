# Phase 2: Enterprise Authentication - Discussion Log

> **Audit trail only.** Do not use as input to planning, research, or execution agents.
> Decisions are captured in CONTEXT.md — this log preserves the alternatives considered.

**Date:** 2026-05-23
**Phase:** 2-Enterprise Authentication
**Areas discussed:** IdP Config Storage, SSO-to-Local User Linking, SCIM Token Model, JIT Role Assignment

---

## IdP Config Storage

| Option | Description | Selected |
|--------|-------------|----------|
| Separate tables | Dedicated tenant_oauth_config and tenant_saml_config tables with typed columns | ✓ |
| JSONB tenants.config | Reuse existing tenants.config JSONB field | |
| Hybrid | Separate table for SAML, JSONB for OAuth2 | |

**User's choice:** Separate tables (Recommended)
**Notes:** Better validation, indexing, and migration safety. Matches existing relational pattern (no JPA, pure JDBC).

| Option | Description | Selected |
|--------|-------------|----------|
| One OAuth2 + one SAML per tenant | Single-row tables keyed by tenant_id | |
| Multiple IdPs of each type | Composite key id + tenant_id, supports Google + Azure AD | ✓ |

**User's choice:** Multiple IdPs of each type per tenant
**Notes:** Covers real-world enterprise use cases where a tenant has multiple identity providers.

| Option | Description | Selected |
|--------|-------------|----------|
| Curated subset | provider_name, client_id, client_secret, issuer_uri, scopes, enabled | ✓ |
| Full Spring schema | All Spring Security client registration fields | |

**User's choice:** Curated subset (Recommended)
**Notes:** Simpler validation, smaller migration, less attack surface. Extra fields can be added later.

| Option | Description | Selected |
|--------|-------------|----------|
| PEM text in DB | Full certificate stored in TEXT column | |
| Thumbprint + lookup | Store SHA-256 thumbprint, fetch cert from IdP metadata URL | ✓ |

**User's choice:** Thumbprint only + cert lookup
**Notes:** Smaller DB footprint, auto-updates when IdP rotates certs. Requires network call per auth.

---

## SSO-to-Local User Linking

| Option | Description | Selected |
|--------|-------------|----------|
| Link to same user row | Same row, passwordHash stays as-is, dual login possible | ✓ |
| Separate SSO-only row | New row with null passwordHash and source=SSO flag | |
| Auto-link with override | Same row, passwordHash set to null, forces SSO-only | |

**User's choice:** Link to same user row (Recommended)
**Notes:** One user = one identity. SSO login just updates lastLoginAt.

| Option | Description | Selected |
|--------|-------------|----------|
| Track auth source | Add auth_source column/claim: LOCAL, OAUTH2, SAML | ✓ |
| Don't track | Session is just a JWT, auth method not recorded | |

**User's choice:** Track auth source per session
**Notes:** Useful for audit logs and compliance.

| Option | Description | Selected |
|--------|-------------|----------|
| Allow password setup | JIT user gets 'Set Password' email, can use either method | |
| Block — SSO-only | JIT users have null passwordHash, must always use SSO | ✓ |
| You decide | Claude picks recommended approach | |

**User's choice:** Block — SSO-only
**Notes:** Simple and secure. JIT-provisioned users cannot use email/password login.

| Option | Description | Selected |
|--------|-------------|----------|
| Yes — one email = one user | LOWER(email) + tenant_id unique index | ✓ |
| No — allow same email | Unique on (email, tenant_id, auth_source) | |

**User's choice:** Yes — one email = one user per tenant
**Notes:** Consistent with "link to same row" decision.

---

## SCIM Token Model

| Option | Description | Selected |
|--------|-------------|----------|
| Separate scim_tokens table | Dedicated table, no user_id, cleaner separation | ✓ |
| Reuse api_keys table | Add 'scim' scope and type column | |

**User's choice:** Separate scim_tokens table (Recommended)
**Notes:** SCIM acts on behalf of the IdP/tenant, not a specific user.

| Option | Description | Selected |
|--------|-------------|----------|
| All-powerful per tenant | Any valid SCIM token can CRUD any user in tenant | |
| Scoped | Tokens carry scopes like scim:users:read, scim:users:write | ✓ |

**User's choice:** Scoped
**Notes:** Matches existing API key scope pattern.

| Option | Description | Selected |
|--------|-------------|----------|
| Admin creates via API | POST to generate, raw token returned once, never shown again | ✓ |
| Auto-generated on config | SCIM token bundled with IdP config response | |

**User's choice:** Admin creates via settings page/API, one-time display
**Notes:** Standard pattern, secure distribution.

| Option | Description | Selected |
|--------|-------------|----------|
| Dedicated ScimTokenAuthFilter | Separate filter for /scim/** paths | ✓ |
| Extend ApiKeyAuthFilter | Modify existing filter to also check scim_tokens | |

**User's choice:** Dedicated ScimTokenAuthFilter
**Notes:** Clean separation of concerns.

---

## JIT Role Assignment

| Option | Description | Selected |
|--------|-------------|----------|
| Configurable per IdP | Add default_role column to IdP config tables | ✓ |
| Hardcoded VIEWER | All JIT users get VIEWER regardless of IdP | |

**User's choice:** Configurable per IdP (Recommended)
**Notes:** Future-proof without extra work now.

| Option | Description | Selected |
|--------|-------------|----------|
| IdP default_role for SCIM | Same default for both SSO JIT and SCIM creation | ✓ |
| Role from SCIM request | Use custom extension attribute if present, fallback to default | |

**User's choice:** IdP default_role for SCIM too
**Notes:** One source of truth per IdP.

| Option | Description | Selected |
|--------|-------------|----------|
| Yes — require local admin | Verify at least one ADMIN with email/password before saving SSO config | ✓ |
| No — trust the admin | Admin can configure whatever they want | |

**User's choice:** Yes — require local admin
**Notes:** Prevents lockout if SSO breaks.

| Option | Description | Selected |
|--------|-------------|----------|
| Preserve existing roles | Existing local ADMIN stays ADMIN when linking to SSO | ✓ |
| Override with default_role | SSO login resets user roles to IdP default | |

**User's choice:** Preserve existing roles
**Notes:** Respects existing permissions.

---

## Claude's Discretion

None — all decisions were user-selected.

## Deferred Ideas

- SCIM Groups push for role mapping — v1.1
- Per-tenant SSO enforcement (block email/password) — v1.1
- IdP-initiated SAML SSO — v1.1
- SAML attribute mapping (IdP claim → Chorus role) — v1.1
