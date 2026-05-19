# Publishing Chorus Engine to Maven Central

> **Date:** 2026-05-19
> **Researcher:** Kimi Code CLI
> **Target:** `io.github.muhibnayem:chorus-engine-*` artifacts on Maven Central

---

## Critical Context: The Old Way Is Dead

**Sonatype OSSRH (`oss.sonatype.org`, `s01.oss.sonatype.org`) was shut down on June 30, 2025.**

If you read old guides mentioning `nexus-publish`, `gradle-nexus`, `maven { url = "https://s01.oss.sonatype.org/..." }`, or manual "Close + Release" in a web UI — **those are all obsolete.**

The only way to publish to Maven Central as of 2026 is through the **Central Portal** (`central.sonatype.com`).

---

## The Central Portal Publishing Flow (2026)

```
┌─────────────────┐     ┌──────────────────┐     ┌─────────────────┐     ┌─────────────────┐
│  Build + Sign   │────▶│  Staging Upload  │────▶│  Validation     │────▶│  Auto-Publish   │
│  (Gradle local) │     │  (REST API)      │     │  (Central Portal)│    │  (Maven Central)│
└─────────────────┘     └──────────────────┘     └─────────────────┘     └─────────────────┘
```

Unlike OSSRH, there is **no manual "Close + Release" UI step** if you use the Publisher API with automatic release enabled.

---

## Step 1: Register a Central Portal Account

1. Go to **https://central.sonatype.com**
2. Sign up with GitHub, Google, or email
3. Verify your email

### Namespace Registration

Chorus Engine uses group ID **`com.chorus`**. You must prove ownership:

| Namespace Pattern | Proof Required |
|---|---|
| `com.chorus` | TXT record in DNS for `chorus.com` domain, OR GitHub repo at `github.com/chorus/...` |
| `io.github.chorus-engine` | Ownership of `github.com/MuhibNayem` organization |
| `com.github.chorus-engine` | Ownership of `github.com/MuhibNayem` organization |

**If you don't own `chorus.com`**, register under `io.github.chorus-engine` instead. This is instant — Sonatype verifies it by checking your GitHub profile.

**Path:** `central.sonatype.com` → Publishing Settings → Namespaces → Register Namespace

---

## Step 2: Generate a User Token

Central Portal does **not** use your login password for publishing. You must generate an API token:

1. Log in to https://central.sonatype.com
2. Click your username → **View Account**
3. Click **Generate User Token**
4. Copy the **Username** and **Password** values

These look like:
```
Username:  aBcDef1/
Password:  aBCdEFG1H2IjKlmnoPQ3R+STUVw4XYzAB5C6dEF7GH8I
```

Store these as GitHub Secrets (see Step 6).

---

## Step 3: Create a GPG Signing Key

Maven Central **requires** all artifacts to be signed with GPG.

```bash
# Generate a new key (RSA 4096, no expiry recommended for CI)
gpg --full-generate-key

# List your keys to get the LONG key ID
gpg --list-secret-keys --keyid-format LONG

# Export public key (publish to keyserver)
gpg --armor --export YOUR_LONG_KEY_ID > public.key
gpg --keyserver keyserver.ubuntu.com --send-keys YOUR_LONG_KEY_ID

# Export private key (for CI signing)
gpg --armor --export-secret-keys YOUR_LONG_KEY_ID > private.key
```

**For GitHub Actions**, base64-encode the private key:
```bash
gpg --armor --export-secret-keys YOUR_LONG_KEY_ID | base64 -w0
```

---

## Step 4: Choose a Publishing Strategy

There are **three viable approaches** for Gradle + Central Portal in 2026:

| Approach | Complexity | Multi-Module | Recommendation |
|---|---|---|---|
| **Vanniktech Maven Publish Plugin** | Low | ✅ Excellent | **Recommended for Chorus** |
| **JReleaser** | Medium | ⚠️ Limited | Good for single-module or release-heavy projects |
| **Raw `maven-publish` + OSSRH Staging API** | High | ✅ Works | Most control, most boilerplate |

### Recommended: Vanniktech Plugin (v0.33.0+)

This is the dominant community plugin. Version 0.33.0 (June 2025) added full Central Portal support.

**Pros:**
- One `mavenPublishing { }` block per module
- Automatic source/javadoc jar generation
- Automatic GPG signing
- Automatic Central Portal upload + release
- Works perfectly with multi-module Gradle projects

**Cons:**
- Additional third-party plugin dependency

---

## Step 5: Gradle Configuration (Vanniktech Plugin)

### 5.1 Root `build.gradle.kts`

Add the plugin to the root build file (applied to subprojects):

```kotlin
plugins {
    java
    `java-library`
    id("io.spring.dependency-management") version "1.1.7" apply false
    id("org.springframework.boot") version "4.0.0" apply false
    id("org.graalvm.buildtools.native") version "0.10.6" apply false
    id("com.vanniktech.maven.publish") version "0.33.0" apply false
}
```

### 5.2 Per-Module Configuration

Add to **each publishable module's** `build.gradle.kts`:

```kotlin
plugins {
    id("com.vanniktech.maven.publish")
}

mavenPublishing {
    publishToMavenCentral(SonatypeHost.CENTRAL_PORTAL, automaticRelease = true)
    signAllPublications()

    coordinates(
        groupId = "com.chorus",
        artifactId = project.name,  // e.g. "chorus-engine-core"
        version = project.version.toString()
    )

    pom {
        name.set("Chorus Engine — ${project.name}")
        description.set("Java-native agentic AI framework")
        inceptionYear.set("2025")
        url.set("https://github.com/MuhibNayem/chorus-engine4j")

        licenses {
            license {
                name.set("Apache License 2.0")
                url.set("https://www.apache.org/licenses/LICENSE-2.0.txt")
            }
        }

        developers {
            developer {
                id.set("chorus-team")
                name.set("Chorus Engine Team")
                email.set("team@chorus.dev")
            }
        }

        scm {
            url.set("https://github.com/MuhibNayem/chorus-engine4j")
            connection.set("scm:git:git://github.com/MuhibNayem/chorus-engine4j.git")
            developerConnection.set("scm:git:ssh://git@github.com:chorus-engine/chorus-engine.git")
        }
    }
}
```

### 5.3 Modules to Publish

| Module | Publish? | Reason |
|---|---|---|
| `chorus-engine-core` | ✅ Yes | Core API |
| `chorus-engine-tokenizer` | ✅ Yes | Token counting |
| `chorus-engine-llm` | ✅ Yes | LLM client |
| `chorus-engine-agent` | ✅ Yes | Agent loop |
| `chorus-engine-graph` | ✅ Yes | Graph execution |
| `chorus-engine-swarm` | ✅ Yes | Swarm orchestration |
| `chorus-engine-harness` | ✅ Yes | Evaluation harness |
| `chorus-engine-tools` | ✅ Yes | Tool registry |
| `chorus-engine-guardrails` | ✅ Yes | Guardrails |
| `chorus-engine-skills` | ✅ Yes | Skill routing |
| `chorus-engine-telemetry` | ✅ Yes | Metrics/OTel |
| `chorus-engine-mcp` | ✅ Yes | MCP protocol |
| `chorus-engine-a2a` | ✅ Yes | A2A protocol |
| `chorus-engine-evals` | ✅ Yes | Evaluation framework |
| `chorus-engine-memory` | ✅ Yes | Memory management |
| `chorus-engine-rag` | ✅ Yes | RAG pipeline |
| `chorus-engine-spring-boot-starter` | ✅ Yes | Spring Boot integration |
| `chorus-engine-spring-boot-sample` | ❌ No | Demo app — not a library |

### 5.4 `gradle.properties` (Local Development)

Create `~/.gradle/gradle.properties`:

```properties
# Central Portal credentials (from Step 2)
mavenCentralUsername=aBcDef1/
mavenCentralPassword=aBCdEFG1H2IjKlmnoPQ3R+STUVw4XYzAB5C6dEF7GH8I

# GPG signing (from Step 3)
signing.keyId=YOUR_KEY_ID_LAST_8_CHARS
signing.password=YOUR_GPG_PASSPHRASE
signing.secretKeyRingFile=/home/you/.gnupg/secring.gpg
```

**Note:** On newer GPG versions (2.1+), the secret key ring is stored in `~/.gnupg/private-keys-v1.d/`. Export it:
```bash
gpg --export-secret-keys YOUR_KEY_ID > ~/.gnupg/secring.gpg
```

---

## Step 6: GitHub Actions Automation

### 6.1 Required Secrets

Add these in GitHub repo → Settings → Secrets and variables → Actions:

| Secret Name | Value Source |
|---|---|
| `MAVEN_CENTRAL_USERNAME` | Central Portal User Token "Username" |
| `MAVEN_CENTRAL_PASSWORD` | Central Portal User Token "Password" |
| `SIGNING_KEY_ID` | Last 8 chars of GPG key ID |
| `SIGNING_PASSWORD` | GPG key passphrase |
| `SIGNING_SECRET_KEY` | Base64-encoded private key (`gpg --export-secret-keys --armor KEY_ID \| base64 -w0`) |

### 6.2 Release Workflow

Create `.github/workflows/release.yml`:

```yaml
name: Release to Maven Central

on:
  push:
    tags:
      - 'v*'

jobs:
  release:
    name: Publish Release
    runs-on: ubuntu-latest
    defaults:
      run:
        working-directory: chorus-engine-java

    steps:
      - uses: actions/checkout@v4

      - name: Set up Java 25
        uses: actions/setup-java@v4
        with:
          java-version: '25'
          distribution: 'oracle'

      - name: Cache Gradle
        uses: actions/cache@v4
        with:
          path: |
            ~/.gradle/caches
            ~/.gradle/wrapper
          key: ${{ runner.os }}-gradle-release-${{ hashFiles('**/*.gradle*', '**/gradle-wrapper.properties') }}

      - name: Import GPG key
        run: |
          echo "${{ secrets.SIGNING_SECRET_KEY }}" | base64 -d | gpg --batch --import

      - name: Grant execute permission for Gradle
        run: chmod +x gradlew

      - name: Build and test
        run: ./gradlew test --no-daemon

      - name: Publish to Maven Central
        run: ./gradlew publish --no-daemon
        env:
          MAVEN_CENTRAL_USERNAME: ${{ secrets.MAVEN_CENTRAL_USERNAME }}
          MAVEN_CENTRAL_PASSWORD: ${{ secrets.MAVEN_CENTRAL_PASSWORD }}
          SIGNING_KEY_ID: ${{ secrets.SIGNING_KEY_ID }}
          SIGNING_PASSWORD: ${{ secrets.SIGNING_PASSWORD }}
```

### 6.3 Snapshot Workflow (Optional)

For `-SNAPSHOT` versions on every push to `main`:

```yaml
name: Publish SNAPSHOT

on:
  push:
    branches: [main]
    paths: ['chorus-engine-java/**']

jobs:
  snapshot:
    name: Publish SNAPSHOT
    runs-on: ubuntu-latest
    defaults:
      run:
        working-directory: chorus-engine-java
    steps:
      - uses: actions/checkout@v4
      - uses: actions/setup-java@v4
        with:
          java-version: '25'
          distribution: 'oracle'
      - run: chmod +x gradlew
      - run: ./gradlew publish --no-daemon
        env:
          MAVEN_CENTRAL_USERNAME: ${{ secrets.MAVEN_CENTRAL_USERNAME }}
          MAVEN_CENTRAL_PASSWORD: ${{ secrets.MAVEN_CENTRAL_PASSWORD }}
          SIGNING_KEY_ID: ${{ secrets.SIGNING_KEY_ID }}
          SIGNING_PASSWORD: ${{ secrets.SIGNING_PASSWORD }}
```

**Note:** For SNAPSHOTs, change the version in `build.gradle.kts` to end with `-SNAPSHOT`, e.g.:
```kotlin
version = "0.1.0-SNAPSHOT"
```

---

## Step 7: Manual Publish (First-Time Verification)

Before automating, verify the full flow manually:

```bash
cd chorus-engine-java

# 1. Ensure version is NOT a SNAPSHOT
# Edit build.gradle.kts: version = "0.1.0"

# 2. Clean build
./gradlew clean

# 3. Run all tests
./gradlew test

# 4. Publish to local staging (dry run)
./gradlew publishToMavenLocal

# 5. Publish to Central Portal
./gradlew publish

# 6. Check deployment status at:
# https://central.sonatype.com/publishing/deployments
```

With `automaticRelease = true`, the deployment should auto-publish after validation (~5-15 minutes).

---

## Step 8: BOM (Bill of Materials) — Strongly Recommended

For a 17-module project, users will thank you for a BOM:

```kotlin
// chorus-engine-bom/build.gradle.kts
plugins {
    id("java-platform")
    id("com.vanniktech.maven.publish")
}

dependencies {
    constraints {
        api(project(":chorus-engine-core"))
        api(project(":chorus-engine-llm"))
        api(project(":chorus-engine-agent"))
        // ... all 17 modules
    }
}
```

Users then declare:
```kotlin
dependencies {
    implementation(platform("io.github.muhibnayem:chorus-engine-bom:0.1.0"))
    implementation("io.github.muhibnayem:chorus-engine-core")
    implementation("io.github.muhibnayem:chorus-engine-llm")
    // No version needed!
}
```

---

## Common Pitfalls

| Pitfall | Solution |
|---|---|
| "Invalid namespace" | Verify namespace at central.sonatype.com/publishing/namespaces |
| "Unauthorized" | Use **User Token**, not your login password |
| "Missing Signature" | Ensure `signAllPublications()` is configured and GPG key is valid |
| "Invalid POM" | Must include: name, description, url, licenses, developers, scm |
| "No sources/javadoc jar" | Vanniktech plugin generates these automatically |
| JReleaser multi-module issues | Switch to Vanniktech plugin — it's the dominant solution for multi-module Gradle |
| "Staging repository not found" | OSSRH-style staging is gone. Use Central Portal Publisher API |
| Gradle 8.x + Java 25 | Use Gradle 9.1.0+ (Chorus already upgraded) |

---

## Timeline Estimate

| Task | Time |
|---|---|
| Register Central Portal account + namespace | 10 min (instant for `io.github.*`, 1-2 days for domain verification) |
| Generate User Token | 2 min |
| Create GPG key + publish to keyserver | 5 min |
| Add Vanniktech plugin + POM config | 30 min |
| Configure GitHub Actions secrets | 10 min |
| First manual publish + verify | 20 min |
| Wait for Maven Central sync | 15-30 min |
| **Total** | **~2 hours** (plus namespace verification if using custom domain) |

---

## Alternative: JReleaser (If You Prefer)

If you want a single unified release tool (GitHub Release + Maven Central + changelogs):

```kotlin
plugins {
    id("org.jreleaser") version "1.17.0"
}

jreleaser {
    signing {
        active = Active.ALWAYS
        armored = true
        mode = Signing.Mode.MEMORY
        passphrase = System.getenv("JRELEASER_GPG_PASSPHRASE")
        publicKey = System.getenv("JRELEASER_GPG_PUBLIC_KEY")
        secretKey = System.getenv("JRELEASER_GPG_SECRET_KEY")
    }
    deploy {
        maven {
            mavenCentral.create("sonatype") {
                active = Active.ALWAYS
                url = "https://central.sonatype.com/api/v1/publisher"
                stagingRepository(layout.buildDirectory.dir("staging-deploy").get().toString())
                applyMavenCentralRules = true
                sign = true
                checksums = true
                sourceJar = true
                javadocJar = true
            }
        }
    }
}
```

**Caveat:** JReleaser has documented limitations with complex multi-module Gradle projects. For Chorus Engine's 17 modules, Vanniktech's plugin is the safer choice.

---

## References

- [Sonatype Central Portal](https://central.sonatype.com)
- [Vanniktech Gradle Maven Publish Plugin](https://github.com/vanniktech/gradle-maven-publish-plugin)
- [Central Portal Publisher API Docs](https://central.sonatype.org/publish/publish-portal-api/)
- [JReleaser Maven Central Guide](https://jreleaser.org/guide/latest/examples/maven/maven-central.html)
- [Endofline Blog: Migrate to Central Portal](https://www.endoflineblog.com/migrate-maven-central-publishing-to-central-portal-for-a-gradle-project)
