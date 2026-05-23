# Stack Research: Enterprise Feature Parity

**Researched:** 2026-05-23
**Confidence:** HIGH (all versions verified against Maven Central and GitHub releases)

> **Platform correction:** The milestone context described Spring Boot 3.x / Java 21.
> The actual build files (`chorus-observe-server/build.gradle.kts`) target
> **Spring Boot 4.0.0 / Java 25** with preview features enabled.
> However, the same build file explicitly pins Spring Security at **6.5.0** (lines 47–49),
> overriding the Boot 4.0.0 BOM which would otherwise pull Security 7.0.0. This is an
> unsupported combination and a blocker for SSO work — see the prerequisite note below.

---

## SSO / OAuth2 / SAML

### PREREQUISITE: Spring Security 6.5 → 7.0 Migration (Blocker for SAML2)

`chorus-observe-server/build.gradle.kts` explicitly pins:

```kotlin
implementation("org.springframework.security:spring-security-crypto:6.5.0")
implementation("org.springframework.security:spring-security-config:6.5.0")
implementation("org.springframework.security:spring-security-web:6.5.0")
```

These explicit version pins override the Boot 4.0.0 BOM. `spring-boot-starter-security-saml2:4.0.0`
transitively requires Spring Security 7.0.x APIs. With the 6.5.0 pins in place, Gradle will resolve
to 6.5.0 (lower version wins in conflict) and the SAML2 starter will fail at runtime against missing
Security 7 APIs.

**Required action before any SSO work:** Remove the three explicit `6.5.0` version pins and let the
Boot 4.0.0 BOM manage the security version to 7.0.0. Then verify the existing JWT filter chain (using
`jjwt:0.12.6`) still compiles — Spring Security 6 → 7 has breaking API changes including the removal
of `WebSecurityConfigurerAdapter` (already removed in 5.7 but also notable) and changes to
`HttpSecurity` DSL. The JWT filter chain likely needs inspection; this is a scoping task for the
requirements writer, estimated at 1–3 days.

**This is not a free transitive upgrade.** It should be scoped as a prerequisite story to the
SSO milestone.

---

### SAML2 Service Provider

**Artifact:** `org.springframework.boot:spring-boot-starter-security-saml2:4.0.0`
(confirmed present on Maven Central; versions 4.0.0 through 4.0.6 confirmed)

Use the Boot starter, not the raw `spring-security-saml2-service-provider` artifact directly.
The starter wires autoconfiguration, pulls `spring-security-saml2-service-provider:7.0.0`, and
keeps the OpenSAML 5 transitive dep in sync with what Boot expects. The older
`spring-security.extensions:spring-security-saml2-core` is archived and incompatible with
Security 7.

**Spring Security version bundled with Boot 4.0.0 BOM:** `7.0.0`
(latest patch in the 7.0.x series as of research date is `7.0.5`; override via
`spring-security.version=7.0.5` in `gradle.properties` if you need the latest patch).

**Minimal application.yml configuration (no extra beans needed for IdP metadata URL):**

```yaml
spring:
  security:
    saml2:
      relyingparty:
        registration:
          okta:
            assertingparty:
              metadata-uri: https://your-idp.example.com/saml/metadata
```

**Key integration notes:**
- Coexists with existing JWT filter chain by declaring a second `SecurityFilterChain` bean with
  `@Order` — give the SAML chain Order 1, JWT chain Order 2.
- `RelyingPartyRegistrationRepository` is autoconfigured from YAML; only override if you need
  dynamic IdP registration from a database (multi-tenant SAML).
- Single Logout (SLO) supported natively via `.saml2Logout(withDefaults())`.
- OpenSAML 5 is the transitive dependency; it ships without the legacy OpenSAML 3 classes.

**Why not Keycloak adapter / Spring Authorization Server:** Both are identity providers (IdPs),
not service provider libraries. The observe-server needs to act as a SAML2 service provider
consuming an external IdP (Okta, Azure AD, ADFS).

---

### OAuth2 / OIDC Client

**Artifact:** `org.springframework.boot:spring-boot-starter-oauth2-client:4.0.0`
(confirmed present on Maven Central for 4.0.0)

Covers Google, GitHub, and any OIDC-compliant IdP (Azure AD, Auth0, Okta OIDC). Spring Boot
autoconfigures `OAuth2LoginConfigurer` from `spring.security.oauth2.client.*` properties.
Also requires the Security 6.5 → 7.0 prerequisite above.

**Why not Spring Authorization Server:** The observe-server is an OAuth2 client/SP, not an IdP.

---

## SCIM v2

**Artifact:** `com.unboundid.product.scim2:scim2-sdk-common:6.0.0`

Released 2026-05-11. Version 6.0.0 is the first release explicitly targeting Jackson 3
(`tools.jackson.*` group), aligning with Spring Framework 7 / Spring Boot 4. Earlier versions
(4.x, 5.x) use Jackson 2 (`com.fasterxml.jackson.*`) and will conflict with Boot 4's Jackson 3
classpath.

**Maven coordinates (Gradle):**

```kotlin
implementation("com.unboundid.product.scim2:scim2-sdk-common:6.0.0")
// Add scim2-sdk-server ONLY if you want JAX-RS resource base classes (not recommended for Spring MVC)
```

Use `scim2-sdk-common` as a model and filter-parsing library wired to Spring MVC `@RestController`
endpoints. For a provisioning endpoint scope (Users + Groups CRUD plus `/ServiceProviderConfig`),
the SDK's `UserResource`, `GroupResource`, `SCIMFilter` parsing, and `PatchRequest` deserialization
are all in `scim2-sdk-common`. The JAX-RS runtime from `scim2-sdk-server` conflicts with Spring MVC
and should not be added.

**What NOT to import:** `scim2-sdk-server` as a runtime dependency (JAX-RS). `scim2-sdk-client`
(for consuming a SCIM provider, not being one). Any version below 6.0.0 (Jackson 2 conflict).

**Alternative considered — roll-your-own with Jackson:** Viable for simple read/write, but SCIM
filter syntax (`?filter=userName eq "john"`) parsing is non-trivial (RFC 7644 §3.4.2.2). Using
`scim2-sdk-common` for parsing saves that work for free.

---

## Parquet Export (Hadoop-free)

**Artifact:** `com.jerolba:carpet-record:0.7.1`
(released 2026-05-16, verified latest on Maven Central)

Carpet serializes Java Records directly to Parquet via reflection — no schema DSL, no code gen,
no Hadoop cluster. The README states "minimized parquet-java and hadoop transitive dependencies."

**Verified from the `0.7.1` POM:** Carpet pulls `hadoop-common:3.4.1` at compile scope but applies
heavy exclusions (zookeeper, jetty, jersey, netty, avro, hadoop-auth, curator, and others),
leaving only a small set of Hadoop utility classes. Carpet then bypasses the HDFS abstraction
entirely by implementing `InputFile`/`OutputFile` against `java.io.OutputStream` and `java.io.File`.
Net result: no HDFS, no Kerberos, no ZooKeeper, no networking stack — only ~500KB of residual
Hadoop utility code.

**This is the correct "Hadoop-free" framing:** not zero-Hadoop-JAR, but zero Hadoop cluster
dependency. The residual hadoop-common classes are inert utilities.

**Gradle dependency:**

```kotlin
implementation("com.jerolba:carpet-record:0.7.1")
```

**Usage pattern for trace export:**

```java
record TraceExportRow(String traceId, String spanId, String service,
                      long durationMs, String status, Instant timestamp) {}

try (OutputStream out = new FileOutputStream("traces.parquet")) {
    try (CarpetWriter<TraceExportRow> writer =
             new CarpetWriter<>(out, TraceExportRow.class)) {
        writer.write(rows);
    }
}
```

Nested Records and Collections are supported — this covers the nested span/attribute structures
in OTLP trace data.

**Alternative considered — parquet-floor:**
- Last updated 2026-05-21, not archived, 60 stars.
- Simpler API but does not support nested types or collections.
- Cannot represent OTLP span attribute maps natively.
- Eliminated: trace data requires nested types.

**Alternative considered — Apache parquet-java directly (without Carpet):**
- Requires hand-writing `MessageType` schema and `GroupWriteSupport` — significant boilerplate.
- Requires manually implementing `InputFile`/`OutputFile` against `java.io` to avoid Hadoop.
- Carpet is exactly this work already done.

---

## S3 Export

**BOM:** `software.amazon.awssdk:bom:2.44.12`
(latest stable as of 2026-05-23, verified from Maven Central release metadata)

**Service artifact:** `software.amazon.awssdk:s3` (version managed by BOM)

**Gradle setup:**

```kotlin
implementation(platform("software.amazon.awssdk:bom:2.44.12"))
implementation("software.amazon.awssdk:s3")
```

For large Parquet files (trace exports can exceed 100MB), add Transfer Manager for multipart
upload:

```kotlin
implementation("software.amazon.awssdk:s3-transfer-manager")
```

For export files consistently under 100MB, skip Transfer Manager.

**Credentials:** Use `DefaultCredentialsProvider` (env vars → instance profile → ECS → EC2).
No configuration needed in code — SDK picks it up automatically.

**Why AWS SDK v2 over v1:** v1 (`aws-java-sdk`) is in maintenance mode and a monolith classpath.
v2 offers per-service artifacts and non-blocking I/O. For S3-only work, the classpath footprint
is minimal: `s3`, `auth`, `regions`, `utils` (all transitive).

**What NOT to add:** `aws-java-sdk` (v1 monolith), Spring Cloud AWS (adds unnecessary context
overhead for export-only use), S3 Transfer Manager unless file sizes justify multipart uploads.

---

## Automated Eval Generation

**Approach:** Prompting pattern over `chorus-engine-llm` (already in the project). No new library.

There is no established Java library for LLM-based test case generation that adds value beyond
what `chorus-engine-llm` already provides. The domain pattern is:

1. **Seed capture:** At trace ingestion time, flag high-value traces using a heuristic filter
   (token count outlier, latency spike, error flag, novel prompt hash). Store flagged trace IDs.
2. **Generation job:** A scheduled `@Scheduled` or `@Async` job picks up flagged traces, calls
   the LLM with a structured system prompt requesting N test assertions as JSON.
3. **Schema:** `(input_prompt, expected_output_pattern, eval_type, threshold, source_trace_id)`.
   Persist to PostgreSQL. The `chorus-engine-evals` module is the natural home.
4. **Cost gate:** Apply a sampling rate (1% of traces, or only manually flagged). Use a cheap
   model (GPT-4o-mini, Claude Haiku 3.5) for generation — not the production model. At ~500
   tokens per generation call, 1000 auto-generated evals cost sub-$1.

**What NOT to add:** LangChain4j (heavyweight dep for a task `chorus-engine-llm` covers),
dedicated "eval generation" frameworks (none exist in Java that add real value here).

---

## CI/CD Eval Gate

**Approach:** JUnit Platform Launcher API + custom Gradle task + GitHub Actions. No new runtime
dependencies.

**Pattern:**

1. A Gradle task (`evalGate`) calls the Chorus Observe API (`GET /api/evals/summary`), reads the
   pass rate and count, and emits JUnit XML to `build/test-results/eval-gate/`. If pass rate is
   below the configured threshold, the task exits non-zero (failing the CI step).
2. `dorny/test-reporter@v1` reads the JUnit XML and posts results to the GitHub PR check,
   annotating failed evals inline.

**Threshold config (in `gradle.properties` or passed as project properties):**

```properties
evalGate.passRateThreshold=0.85
evalGate.minEvalCount=50
```

**GitHub Actions step:**

```yaml
- name: Run eval gate
  run: ./gradlew evalGate -PevalGate.passRateThreshold=0.85
  env:
    CHORUS_OBSERVE_URL: ${{ secrets.CHORUS_OBSERVE_STAGING_URL }}
    CHORUS_OBSERVE_TOKEN: ${{ secrets.CHORUS_OBSERVE_TOKEN }}

- name: Publish eval results
  uses: dorny/test-reporter@v1
  if: always()
  with:
    name: Eval Gate Results
    path: build/test-results/eval-gate/*.xml
    reporter: java-junit
```

**Total new code:** ~50–80 lines of Gradle task. No new runtime deps.

**What NOT to add:** SonarQube, Allure, Testcontainers Cloud, or dedicated quality gate products.
The gate is a threshold check on one API endpoint — purpose-built is both simpler and more correct.

---

## Teams Alerting

**Approach:** Power Automate Workflow webhook with Adaptive Card payload. No new library — use
Spring's `RestClient` (available in Spring 6.1+, on the Boot 4 classpath).

**Critical context:** Office 365 Connectors (classic `outlook.office.com/webhook/...` URLs) were
**retired May 18–22, 2026**. These URLs no longer deliver messages. The replacement is Power
Automate Workflow webhooks, provisioned via the Workflows app in Teams
(channel → ... → Workflows → "Post to a channel when a webhook request is received").

**Payload format:** Adaptive Card JSON. The schema differs from the legacy MessageCard format.
Minimal example:

```json
{
  "type": "message",
  "attachments": [{
    "contentType": "application/vnd.microsoft.card.adaptive",
    "content": {
      "$schema": "http://adaptivecards.io/schemas/adaptive-card.json",
      "type": "AdaptiveCard",
      "version": "1.4",
      "body": [{"type": "TextBlock", "text": "Alert: high error rate on model X", "wrap": true}]
    }
  }]
}
```

**Java implementation (no new dependency):**

```java
// RestClient is part of spring-web, already on classpath via spring-boot-starter-web
RestClient restClient = RestClient.create();
restClient.post()
    .uri(webhookUrl)
    .contentType(MediaType.APPLICATION_JSON)
    .body(adaptiveCardPayload)  // Map<String, Object> serialized by Jackson
    .retrieve()
    .toBodilessEntity();
```

**Why not Microsoft Graph API:** Requires Azure AD app registration, OAuth2 client credentials
flow, and permission grants. Appropriate for two-way bot interactions; overkill for one-way alert
delivery. Power Automate webhook is the correct MVP choice.

**What NOT to add:** `microsoft-graph` SDK, Bot Framework SDK, Adaptive Cards Java rendering
library, or any legacy connector library.

---

## Not Recommended

| What | Why Not |
|------|---------|
| Explicit `spring-security:6.5.0` pins (existing) | Conflict with Boot 4 BOM; must be removed before any SSO work |
| `spring-security.extensions:spring-security-saml2-core` | Archived; incompatible with Security 7 / OpenSAML 5 |
| Keycloak SAML adapter | Keycloak is an IdP, not an SP library |
| Spring Authorization Server | Same — it is an IdP |
| `scim2-sdk-server` as JAX-RS runtime | Conflicts with Spring MVC; use only `scim2-sdk-common` |
| `scim2-sdk-*` versions below 6.0.0 | Use Jackson 2; incompatible with Boot 4's Jackson 3 classpath |
| `parquet-floor` | No nested type support; insufficient for OTLP trace data |
| Apache parquet-java without Carpet | Manual boilerplate that Carpet already handles |
| `aws-java-sdk` (v1) | Maintenance mode monolith |
| Spring Cloud AWS | Over-scoped for export-only S3 writes |
| LangChain4j (for eval generation) | Heavy dep; `chorus-engine-llm` already covers this |
| `outlook.office.com/webhook/...` URLs | Retired May 2026; broken |
| Microsoft Graph SDK (for Teams alerts) | 10x setup complexity vs. Power Automate webhook for one-way alerting |
| SonarQube / Allure (for eval gate) | Purpose-built Gradle task is simpler and more appropriate |

---

## Sources

- `chorus-observe-server/build.gradle.kts` lines 47–49: explicit Security 6.5.0 pins confirmed
- Spring Boot 4.0.0 BOM (`spring-boot-dependencies:4.0.0` on Maven Central): `spring-security.version=7.0.0`
- `spring-boot-starter-security-saml2` Maven metadata: versions 4.0.0–4.0.6 confirmed stable
- `spring-security-saml2-service-provider` Maven metadata: 7.0.0–7.0.5 confirmed stable
- [Spring Security 7.0.4 release blog](https://spring.io/blog/2026/03/19/spring-security-6-5-9-and-7-0-4-and-7-1-0-M3-available-now/)
- PingIdentity SCIM2 SDK 6.0.0 GitHub release (2026-05-11): Jackson 3 / Boot 4 compatibility confirmed in release notes
- `com.unboundid.product.scim2:scim2-sdk-server:6.0.0` POM verified on Maven Central
- Carpet 0.7.1 GitHub release (2026-05-16) and `com.jerolba:carpet-record:0.7.1` POM verified
- AWS SDK v2 BOM `2.44.12` confirmed latest stable (Maven Central release metadata)
- [Microsoft Teams connector retirement announcement](https://devblogs.microsoft.com/microsoft365dev/retirement-of-office-365-connectors-within-microsoft-teams/)
- [Teams webhook migration update — retirement date May 18–22, 2026](https://cloudback.it/blog/update-your-microsoft-teams-notification-webhooks-by-march-31-2026)
- [dorny/test-reporter](https://github.com/dorny/test-reporter): JUnit XML (`java-junit`) reporter confirmed
