# Phase 7: GraalVM Native Image Compatibility — Deep Analysis

> **Date:** 2026-05-19
> **Analyst:** Kimi Code CLI
> **Scope:** `chorus-engine-java/` — all 20 modules, Spring Boot starter, sample app

---

## Executive Summary

**Chorus Engine is closer to native-image compatibility than the Phase 7 plan suggests, but with several non-obvious gotchas.** The codebase has zero ServiceLoader usage, zero dynamic proxies, zero CGLIB/ByteBuddy, and uses JDK HttpClient (all GraalVM-friendly). The real work is: (1) Jackson sealed-type reachability metadata, (2) Spring Boot AOT conditional-class analysis, (3) resource registration for tokenizer files, and (4) fixing `Class.forName` probes that silently fail in native image.

**Estimated effort: 2–3 days of focused work + CI integration.**

---

## 1. Jackson Polymorphism — The Biggest Work Item

### 1.1 Sealed Types Without `@JsonTypeInfo` (6 hierarchies)

Jackson 2.18 handles Java `sealed` types by reflecting on the `permits` clause at runtime. In a native image, this metadata must be explicitly declared.

| Hierarchy | File | Impl Count | Serialization? | Deserialization? | Severity |
|---|---|---|---|---|---|
| `AgentEvent` | `core/event/AgentEvent.java` | 20 records | ✅ EventBus, Provenance | ✅ Checkpoint replay | **Critical** |
| `StreamEvent` | `llm/StreamEvent.java` | 6 records | ✅ SSE streaming | ❌ (produced internally) | **Critical** |
| `GraphEvent<S>` | `graph/state/GraphEvent.java` | 7 records | ✅ Graph streaming | ❌ (produced internally) | **High** |
| `ChorusEvent` | `telemetry/event/ChorusEvent.java` | 9 records | ✅ OTel bridge | ❌ (produced internally) | **High** |
| `McpResult.Content` | `mcp/protocol/McpResult.java` | 3 records | ✅ MCP protocol | ✅ MCP protocol | **Medium** |
| `Result<T,E>` | `core/result/Result.java` | 2 records | ✅ Everywhere | ✅ Everywhere | **Critical** |

**Problem:** Jackson's `BeanDeserializer` for sealed types uses `Class.getPermittedSubclasses()` which requires the sealed class and all permitted subclasses to be registered for reflection. Without this, deserialization throws `InvalidDefinitionException` at runtime.

**Mitigation:** Jackson 2.18 has built-in native-image support via `reflect-config.json` in the classpath. However, it does NOT auto-detect sealed-type hierarchies. You must list every sealed interface, every permitted subclass, and every record component type.

### 1.2 Explicit `@JsonTypeInfo` — A2A `Part`

```java
// chorus-engine-a2a/src/.../a2a/task/Part.java
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "type")
@JsonSubTypes({
    @JsonSubTypes.Type(value = Part.TextPart.class, name = "text"),
    @JsonSubTypes.Type(value = Part.FilePart.class, name = "file"),
    @JsonSubTypes.Type(value = Part.DataPart.class, name = "data")
})
public sealed interface Part { ... }
```

**Status:** Already uses Jackson's explicit type-info mechanism. Native image needs `Part`, `TextPart`, `FilePart`, `DataPart` in `reflect-config.json`. Their constructors and the `type` property getter must be accessible.

**Severity: Medium** — Explicit annotation means Jackson knows what to do; it just needs reflection access.

### 1.3 Custom `@JsonDeserialize` — MCP `JsonRpcMessage`

```java
// chorus-engine-mcp/src/.../mcp/protocol/McpMessage.java
@JsonDeserialize(using = JsonRpcMessageDeserializer.class)
public sealed interface JsonRpcMessage permits ... { ... }
```

**Status:** The custom `StdDeserializer` does structural inspection (checks for `"id"`, `"error"`, `"result"`, `"method"` fields). It does NOT rely on Jackson's polymorphic deserialization. No sealed-type reflection needed.

**However:** The deserializer instantiates records via their canonical constructors. `JsonRpcRequest`, `JsonRpcResponse`, `JsonRpcNotification`, `JsonRpcError`, and `ErrorDetail` must all be registered for reflection so Jackson can read their constructors.

**Severity: Medium**

### 1.4 ObjectMapper Configuration Inconsistency

```java
// ChorusAutoConfiguration.java — the Spring Boot starter
@Bean
@ConditionalOnMissingBean(name = "chorusObjectMapper")
public ObjectMapper chorusObjectMapper() {
    return new ObjectMapper();  // ← NO JavaTimeModule registered
}
```

**Problem:** `AgentEvent` records contain `java.time.Instant` fields. `ChorusEvent` has `Instant`. `GraphEvent` may have `Instant`. Without `JavaTimeModule`, Jackson serializes `Instant` as epoch seconds (number), not ISO-8601 strings. This is a **behavioral bug** even in JVM mode, but it's worse in native image because the JSR-310 module classes need explicit registration.

Some modules DO register it:
- `EvalReportExporter`: `new ObjectMapper().registerModule(new JavaTimeModule())`
- `ProjectMemoryStore`: `new ObjectMapper().registerModule(new JavaTimeModule())`
- `ApprovalLog`: `new ObjectMapper().registerModule(new JavaTimeModule())`

But the Spring Boot starter does not.

**Fix:** Add `JavaTimeModule` to the starter's `ObjectMapper` bean. Add `com.fasterxml.jackson.datatype.jsr310.JavaTimeModule` to reflection config.

**Severity: High** (behavioral + native-image)

---

## 2. Reflection — Less Scary Than It Looks

### 2.1 Production Code Reflection

| Location | Pattern | Purpose | Native-Image Impact |
|---|---|---|---|
| `OpenTelemetryBridge.java:80` | `Class.forName("io.opentelemetry.api.trace.Tracer")` | Probe if OTel on classpath | **Broken in native image** — returns null even if present unless registered |
| `VectorOpsProvider.java:62` | `Class.forName("jdk.incubator.vector.FloatVector")` | Probe for Vector API module | **Broken in native image** — incubator module may not be available at build time |

**`OpenTelemetryBridge` fix:** Replace `Class.forName` with a static boolean flag or use Spring Boot's `@ConditionalOnClass` at the configuration level. The inner `OtelDelegate` class pattern is already designed to delay class loading — but `Class.forName` in native image requires the class to be in the image, which it won't be if it's `compileOnly`.

**Better fix:** Remove the `Class.forName` probe entirely. Rely on Spring Boot's `@ConditionalOnClass(name = "io.opentelemetry.api.trace.Tracer")` which already exists in `ChorusAutoConfiguration`. In non-Spring usage, users pass an `Optional<OtelConfig>` or a null.

**`VectorOpsProvider` fix:** The Vector API is an incubator module (`jdk.incubator.vector`). In native image:
1. The module must be added with `--add-modules jdk.incubator.vector` at image build time.
2. `FloatVector` and related classes must be in `reflect-config.json`.
3. Alternatively, remove the `Class.forName` probe and use a static try-catch around `VectorApiOperations.create()` which will throw `NoClassDefFoundError` if the module is absent.

### 2.2 Test-Only Reflection (Safe)

- `AgentLoopTest`, `HitlGateTest`: Use reflection to reset static `HitlGate.gates` map.
- `VectorOpsProviderTest`: Uses reflection to invoke private constructor.

These are test-only and do not affect the native image.

**Severity: High** (for production code)

---

## 3. ServiceLoader — Zero Usage ✅

No `java.util.ServiceLoader` calls. No `META-INF/services/` files. No `@AutoService` annotations.

This is **excellent** for native-image compatibility. One less category of configuration to manage.

---

## 4. Dynamic Proxies — Zero Usage ✅

No `java.lang.reflect.Proxy`. No CGLIB. No ByteBuddy. No ASM.

---

## 5. Resource Loading — Needs Registration

### 5.1 Tokenizer `.tiktoken` Files

```java
// TokenizerRegistry.java:176
try (InputStream is = getClass().getResourceAsStream(resourcePath)) {
    // resourcePath = "/tokenizers/cl100k_base.tiktoken"
```

**Files referenced:**
- `/tokenizers/cl100k_base.tiktoken`
- `/tokenizers/o200k_base.tiktoken`
- `/tokenizers/llama-3.tiktoken`
- `/tokenizers/deepseek.tiktoken`
- `/tokenizers/qwen.tiktoken`

**Action:** Add to `resource-config.json` in `chorus-engine-tokenizer`:
```json
{
  "resources": {
    "includes": [
      {"pattern": "tokenizers/.*\\.tiktoken$"}
    ]
  }
}
```

**Severity: Critical** — Native image strips unreferenced resources by default. Tokenizers would silently fail with "file not found" and fall back to approximate tokenization.

### 5.2 Skill JSON Files

```java
// SkillLoader.java:54
InputStream is = getClass().getClassLoader().getResourceAsStream(resourcePath);
```

Skills are loaded from user-provided paths (directory or URL). Classpath loading is optional. No built-in skill resources to register.

**Severity: Low** — User's responsibility to register their own skill files.

---

## 6. JDK HttpClient + SSE — No Issue ✅

### 6.1 SSE Parser

```java
// SseParser.java:38
while ((line = reader.readLine()) != null) {
```

**Phase 7 plan concern:** "SSE parsing uses InputStream.read() in a loop — verify no blocking issue with native image thread model"

**Reality:** This is a non-issue. Native Image does not change how blocking I/O works on standard JDK classes. `BufferedReader.readLine()` on an `InputStream` from an HTTP response body works identically in native image. The thread blocking on I/O is standard behavior.

**What WOULD be an issue:** If you were using Netty's event loop with blocking calls, or if you relied on `sun.nio.ch` internals. Neither applies here.

**Severity: None**

### 6.2 HttpClient Virtual Threads

Java 25 uses virtual threads by default for `HttpClient` async operations. Native Image supports virtual threads (Project Loom was stabilized in JDK 21). No concerns.

---

## 7. Caffeine — Not in Codebase ✅

The Phase 7 plan mentions "Caffeine 3.x is GraalVM-compatible." **Caffeine is not a dependency of Chorus Engine.** Zero imports, zero build references. This line item can be removed from the plan.

---

## 8. Spring Boot AOT — The Hidden Complexity

### 8.1 `@ConditionalOnClass` with String Names

```java
// ChorusAutoConfiguration.java:650
@ConditionalOnClass(name = "io.opentelemetry.api.trace.Tracer")
static class TelemetryConfiguration { ... }

// ChorusAutoConfiguration.java:740
@ConditionalOnClass(DataSource.class)
static class JdbcCheckpointerConfiguration { ... }
```

Spring Boot AOT processing evaluates these conditions at build time. For string-based `@ConditionalOnClass(name = ...)`, Spring AOT loads the named class via reflection during AOT analysis. If the class is not on the classpath at build time (OTel is `compileOnly`), the condition evaluates to `false` and the configuration is excluded from the native image.

**This is actually the desired behavior.** OTel users add the OTel dependency, and the configuration is included. Users without OTel don't pay for it.

**Risk:** If a user adds OTel to their application dependencies but NOT to the native-image build classpath, the condition is `false` at build time and the bean is missing at runtime. This is a documentation issue, not a code issue.

### 8.2 `@ConditionalOnProperty` with `matchIfMissing = true`

```java
@ConditionalOnProperty(prefix = "chorus", name = "enabled",
    havingValue = "true", matchIfMissing = true)
```

Spring AOT processes properties at build time using `application.properties`/`application.yml` from the sample/starter. The `application.yml` in the sample explicitly sets many features to `enabled: false`. This means AOT may exclude beans that the user wants to enable at runtime via environment variables.

**This is a known Spring Boot Native limitation.** Properties that change at runtime must be marked as "runtime-initialized" or the conditions must be restructured.

**Fix options:**
1. Document that native-image users must provide an `application.yml` at build time with the features they want.
2. Restructure conditions to use `@ConditionalOnProperty` without `matchIfMissing` for optional features, forcing explicit enablement.
3. Use `@ImportRuntimeHints` to register runtime-initialized classes.

**Severity: Medium** — affects user experience, not correctness.

### 8.3 `@ConditionalOnMissingBean`

Nearly every bean has `@ConditionalOnMissingBean`. In AOT, this is evaluated at build time. If a user provides a custom bean, they must ensure it's present during AOT processing (e.g., via `@Import` in the sample app). This is standard Spring Boot Native behavior.

**Severity: Low** — well-documented Spring Boot Native pattern.

---

## 9. `--enable-preview` (Java 25) — Supported but Tricky

### 9.1 Preview Features Used

From the build config:
```kotlin
tasks.withType<JavaCompile> {
    options.compilerArgs.addAll(listOf("-parameters", "--enable-preview"))
    options.release.set(25)
}
```

Preview features enabled at compile time include:
- **Structured Concurrency** (JEP 480) — `StructuredTaskScope`
- **Stable Values** (JEP 502) — `StableValue`
- **Primitive types in patterns** (JEP 488)
- **Module import declarations** (JEP 494) — `import module java.base;`
- **Flexible constructor bodies** (JEP 492) — `super()` anywhere in constructor

### 9.2 Native Image Compatibility

GraalVM Native Image **does** support `--enable-preview`. You pass it to both the Java compiler AND the native-image builder:

```bash
native-image --enable-preview -cp ...
```

**However:** Preview features can change between JDK versions. If you build a native image with Java 25 preview features, it only runs on a GraalVM runtime that supports Java 25 preview features. This is a deployment constraint, not a build blocker.

**The bigger question:** Does Chorus Engine actually USE any preview features in production code?

**Search result:** No evidence of `StructuredTaskScope`, `StableValue`, or `import module` in the codebase. The `--enable-preview` flag may be a carryover from early development or reserved for future use.

**Recommendation:** Audit the codebase for actual preview feature usage. If none, remove `--enable-preview` to eliminate a native-image compatibility risk.

**Severity: Medium** — if preview features are unused, it's unnecessary risk. If used, it's a documented constraint.

---

## 10. `jdk.incubator.vector` Module

Added at test time: `jvmArgs("--enable-preview", "--add-modules", "jdk.incubator.vector")`

For native image:
1. The module must be on the module path at image build time.
2. `--add-modules jdk.incubator.vector` must be passed to the native-image builder.
3. `VectorApiOperations` and all `jdk.incubator.vector.*` classes it touches must be in `reflect-config.json`.
4. The resulting native binary does NOT need `--add-modules` at runtime (the module is baked into the image).

**Alternative:** Make Vector API support optional. If the module is absent, fall back to FMA or scalar. This is already the design (`VectorOpsProvider.autoDetectImpl()`), but the `Class.forName` probe won't work in native image.

**Fix:** Replace `Class.forName` with a try-catch around `VectorApiOperations.create()`:
```java
private static VectorOperations createVectorApi() {
    try {
        return VectorApiOperations.create();  // throws NoClassDefFoundError if module absent
    } catch (NoClassDefFoundError | Exception e) {
        LOGGER.fine("Vector API not available: " + e.getMessage());
        return null;
    }
}
```

**Severity: Medium**

---

## 11. External Dependencies — Native-Image Status

| Dependency | Version | GraalVM Status | Action Needed |
|---|---|---|---|
| Jackson (databind/core/datatype-jsr310) | 2.18.0 | ✅ Native metadata built-in | Add sealed-type entries to `reflect-config.json` |
| HikariCP | 6.2.1 | ⚠️ Needs reflection config | Register `HikariConfig`, `HikariDataSource`, JDBC driver classes |
| Jedis | 5.2.0 | ⚠️ Needs reflection config | Register `JedisPool`, `Jedis`, `BinaryJedis` constructors |
| OpenTelemetry API/SDK | 1.43.0 | ✅ Native metadata built-in | Ensure OTel is on classpath at AOT time if used |
| SLF4J | 2.0.16 | ✅ Native metadata built-in | Register `StaticLoggerBinder` if using logback |
| jspecify | 1.0.0 | ✅ Annotations only | None (compileOnly) |
| Spring Boot | 4.0.0 | ✅ AOT processing | Configure `org.graalvm.buildtools.native` plugin |

---

## 12. Missing CI for Java

**Critical finding:** The `.github/workflows/ci.yml` is entirely for the TypeScript project at the repo root. There is **no Java CI**.

Before adding a native-image CI job, you need:
1. A basic Java CI job (compile + test) for `chorus-engine-java/`.
2. Then add a native-image job that:
   - Sets up GraalVM CE 25 (or Oracle GraalVM)
   - Runs `./gradlew nativeCompile` on the sample
   - Measures startup time
   - Runs a smoke test against the native binary

**Severity: High** — no CI means regressions are invisible.

---

## 13. Recommendations by Priority

### P0 (Blockers — Must Fix)

1. **Jackson sealed-type reflection config** — Create `reflect-config.json` covering:
   - `AgentEvent` + all 20 record implementations
   - `StreamEvent` + all 6 record implementations
   - `GraphEvent` + all 7 record implementations
   - `ChorusEvent` + all 9 record implementations
   - `Result` + `Ok` + `Err`
   - `Part` + `TextPart` + `FilePart` + `DataPart`
   - `McpResult.Content` + `TextContent` + `ImageContent` + `EmbeddedResourceContent`
   - All record constructors and component getters

2. **Resource config for tokenizers** — Register `tokenizers/*.tiktoken` in `resource-config.json`.

3. **Fix `Class.forName` probes** — Replace with try-catch or Spring `@ConditionalOnClass`.

4. **Register `JavaTimeModule`** — In starter's `ObjectMapper` bean and in reflection config.

### P1 (Important — Should Fix)

5. **Add `org.graalvm.buildtools.native` plugin** to sample app's `build.gradle.kts`.
6. **Add `native-image.properties`** per module with `--enable-preview` and `--add-modules jdk.incubator.vector` if needed.
7. **Add HikariCP + Jedis reflection entries** if JDBC/Redis checkpointers are used.
8. **Create Java CI job** (compile + test) before native-image CI.
9. **Audit for actual preview feature usage** — Remove `--enable-preview` if unused.

### P2 (Nice to Have)

10. **Spring Boot AOT hints** — `@ImportRuntimeHints` for runtime-initialized beans.
11. **Document native-image property constraints** — Users must bake their `application.yml` at build time.
12. **Measure startup time** — Target <100ms on `linux/amd64`.

---

## 14. Detailed `reflect-config.json` Template

```json
[
  {
    "name": "com.chorus.engine.core.event.AgentEvent",
    "allDeclaredConstructors": true,
    "allDeclaredMethods": true
  },
  {
    "name": "com.chorus.engine.core.event.AgentEvent$StreamToken",
    "allDeclaredConstructors": true,
    "allDeclaredMethods": true
  },
  {
    "name": "com.chorus.engine.core.event.AgentEvent$ThinkingStart",
    "allDeclaredConstructors": true,
    "allDeclaredMethods": true
  },
  {
    "name": "com.chorus.engine.core.event.AgentEvent$ThinkingEnd",
    "allDeclaredConstructors": true,
    "allDeclaredMethods": true
  },
  {
    "name": "com.chorus.engine.core.event.AgentEvent$ToolCallStart",
    "allDeclaredConstructors": true,
    "allDeclaredMethods": true
  },
  {
    "name": "com.chorus.engine.core.event.AgentEvent$ToolCallDone",
    "allDeclaredConstructors": true,
    "allDeclaredMethods": true
  },
  {
    "name": "com.chorus.engine.core.event.AgentEvent$ToolCallError",
    "allDeclaredConstructors": true,
    "allDeclaredMethods": true
  },
  {
    "name": "com.chorus.engine.core.event.AgentEvent$RoundStart",
    "allDeclaredConstructors": true,
    "allDeclaredMethods": true
  },
  {
    "name": "com.chorus.engine.core.event.AgentEvent$RoundEnd",
    "allDeclaredConstructors": true,
    "allDeclaredMethods": true
  },
  {
    "name": "com.chorus.engine.core.event.AgentEvent$HitlRequested",
    "allDeclaredConstructors": true,
    "allDeclaredMethods": true
  },
  {
    "name": "com.chorus.engine.core.event.AgentEvent$HitlResolved",
    "allDeclaredConstructors": true,
    "allDeclaredMethods": true
  },
  {
    "name": "com.chorus.engine.core.event.AgentEvent$CheckpointSaved",
    "allDeclaredConstructors": true,
    "allDeclaredMethods": true
  },
  {
    "name": "com.chorus.engine.core.event.AgentEvent$CheckpointLoaded",
    "allDeclaredConstructors": true,
    "allDeclaredMethods": true
  },
  {
    "name": "com.chorus.engine.core.event.AgentEvent$CompactionTriggered",
    "allDeclaredConstructors": true,
    "allDeclaredMethods": true
  },
  {
    "name": "com.chorus.engine.core.event.AgentEvent$GuardrailTriggered",
    "allDeclaredConstructors": true,
    "allDeclaredMethods": true
  },
  {
    "name": "com.chorus.engine.core.event.AgentEvent$MemoryRecall",
    "allDeclaredConstructors": true,
    "allDeclaredMethods": true
  },
  {
    "name": "com.chorus.engine.core.event.AgentEvent$MemoryStore",
    "allDeclaredConstructors": true,
    "allDeclaredMethods": true
  },
  {
    "name": "com.chorus.engine.core.event.AgentEvent$Handoff",
    "allDeclaredConstructors": true,
    "allDeclaredMethods": true
  },
  {
    "name": "com.chorus.engine.core.event.AgentEvent$StreamStart",
    "allDeclaredConstructors": true,
    "allDeclaredMethods": true
  },
  {
    "name": "com.chorus.engine.core.event.AgentEvent$StreamEnd",
    "allDeclaredConstructors": true,
    "allDeclaredMethods": true
  },
  {
    "name": "com.chorus.engine.core.event.AgentEvent$Done",
    "allDeclaredConstructors": true,
    "allDeclaredMethods": true
  },
  {
    "name": "com.chorus.engine.core.event.AgentEvent$Error",
    "allDeclaredConstructors": true,
    "allDeclaredMethods": true
  },
  {
    "name": "com.chorus.engine.core.event.AgentEvent$HitlDecision",
    "allDeclaredConstructors": true,
    "allDeclaredMethods": true
  },
  {
    "name": "com.chorus.engine.core.event.AgentEvent$GuardrailAction",
    "allDeclaredConstructors": true,
    "allDeclaredMethods": true
  },
  {
    "name": "com.chorus.engine.llm.StreamEvent",
    "allDeclaredConstructors": true,
    "allDeclaredMethods": true
  },
  {
    "name": "com.chorus.engine.llm.StreamEvent$Token",
    "allDeclaredConstructors": true,
    "allDeclaredMethods": true
  },
  {
    "name": "com.chorus.engine.llm.StreamEvent$ToolCallStart",
    "allDeclaredConstructors": true,
    "allDeclaredMethods": true
  },
  {
    "name": "com.chorus.engine.llm.StreamEvent$ToolCallDelta",
    "allDeclaredConstructors": true,
    "allDeclaredMethods": true
  },
  {
    "name": "com.chorus.engine.llm.StreamEvent$ToolCallDone",
    "allDeclaredConstructors": true,
    "allDeclaredMethods": true
  },
  {
    "name": "com.chorus.engine.llm.StreamEvent$Finish",
    "allDeclaredConstructors": true,
    "allDeclaredMethods": true
  },
  {
    "name": "com.chorus.engine.llm.StreamEvent$Error",
    "allDeclaredConstructors": true,
    "allDeclaredMethods": true
  },
  {
    "name": "com.chorus.engine.core.result.Result",
    "allDeclaredConstructors": true,
    "allDeclaredMethods": true
  },
  {
    "name": "com.chorus.engine.core.result.Result$Ok",
    "allDeclaredConstructors": true,
    "allDeclaredMethods": true
  },
  {
    "name": "com.chorus.engine.core.result.Result$Err",
    "allDeclaredConstructors": true,
    "allDeclaredMethods": true
  },
  {
    "name": "com.chorus.engine.a2a.task.Part",
    "allDeclaredConstructors": true,
    "allDeclaredMethods": true
  },
  {
    "name": "com.chorus.engine.a2a.task.Part$TextPart",
    "allDeclaredConstructors": true,
    "allDeclaredMethods": true
  },
  {
    "name": "com.chorus.engine.a2a.task.Part$FilePart",
    "allDeclaredConstructors": true,
    "allDeclaredMethods": true
  },
  {
    "name": "com.chorus.engine.a2a.task.Part$DataPart",
    "allDeclaredConstructors": true,
    "allDeclaredMethods": true
  },
  {
    "name": "com.chorus.engine.graph.state.GraphEvent",
    "allDeclaredConstructors": true,
    "allDeclaredMethods": true
  },
  {
    "name": "com.chorus.engine.graph.state.GraphEvent$NodeStart",
    "allDeclaredConstructors": true,
    "allDeclaredMethods": true
  },
  {
    "name": "com.chorus.engine.graph.state.GraphEvent$NodeEnd",
    "allDeclaredConstructors": true,
    "allDeclaredMethods": true
  },
  {
    "name": "com.chorus.engine.graph.state.GraphEvent$EdgeTransition",
    "allDeclaredConstructors": true,
    "allDeclaredMethods": true
  },
  {
    "name": "com.chorus.engine.graph.state.GraphEvent$CheckpointSaved",
    "allDeclaredConstructors": true,
    "allDeclaredMethods": true
  },
  {
    "name": "com.chorus.engine.graph.state.GraphEvent$GraphEnd",
    "allDeclaredConstructors": true,
    "allDeclaredMethods": true
  },
  {
    "name": "com.chorus.engine.graph.state.GraphEvent$GraphError",
    "allDeclaredConstructors": true,
    "allDeclaredMethods": true
  },
  {
    "name": "com.chorus.engine.graph.state.GraphEvent$SpeculativeHit",
    "allDeclaredConstructors": true,
    "allDeclaredMethods": true
  },
  {
    "name": "com.chorus.engine.telemetry.event.ChorusEvent",
    "allDeclaredConstructors": true,
    "allDeclaredMethods": true
  },
  {
    "name": "com.chorus.engine.telemetry.event.AgentStartEvent",
    "allDeclaredConstructors": true,
    "allDeclaredMethods": true
  },
  {
    "name": "com.chorus.engine.telemetry.event.AgentEndEvent",
    "allDeclaredConstructors": true,
    "allDeclaredMethods": true
  },
  {
    "name": "com.chorus.engine.telemetry.event.LlmCallEvent",
    "allDeclaredConstructors": true,
    "allDeclaredMethods": true
  },
  {
    "name": "com.chorus.engine.telemetry.event.ToolCallEvent",
    "allDeclaredConstructors": true,
    "allDeclaredMethods": true
  },
  {
    "name": "com.chorus.engine.telemetry.event.RagQueryEvent",
    "allDeclaredConstructors": true,
    "allDeclaredMethods": true
  },
  {
    "name": "com.chorus.engine.telemetry.event.HandoffEvent",
    "allDeclaredConstructors": true,
    "allDeclaredMethods": true
  },
  {
    "name": "com.chorus.engine.telemetry.event.GuardrailEvent",
    "allDeclaredConstructors": true,
    "allDeclaredMethods": true
  },
  {
    "name": "com.chorus.engine.telemetry.event.CheckpointEvent",
    "allDeclaredConstructors": true,
    "allDeclaredMethods": true
  },
  {
    "name": "com.chorus.engine.telemetry.event.CircuitBreakerEvent",
    "allDeclaredConstructors": true,
    "allDeclaredMethods": true
  },
  {
    "name": "com.chorus.engine.mcp.protocol.McpMessage$JsonRpcMessage",
    "allDeclaredConstructors": true,
    "allDeclaredMethods": true
  },
  {
    "name": "com.chorus.engine.mcp.protocol.McpMessage$JsonRpcRequest",
    "allDeclaredConstructors": true,
    "allDeclaredMethods": true
  },
  {
    "name": "com.chorus.engine.mcp.protocol.McpMessage$JsonRpcResponse",
    "allDeclaredConstructors": true,
    "allDeclaredMethods": true
  },
  {
    "name": "com.chorus.engine.mcp.protocol.McpMessage$JsonRpcNotification",
    "allDeclaredConstructors": true,
    "allDeclaredMethods": true
  },
  {
    "name": "com.chorus.engine.mcp.protocol.McpMessage$JsonRpcError",
    "allDeclaredConstructors": true,
    "allDeclaredMethods": true
  },
  {
    "name": "com.chorus.engine.mcp.protocol.McpMessage$JsonRpcError$ErrorDetail",
    "allDeclaredConstructors": true,
    "allDeclaredMethods": true
  },
  {
    "name": "com.chorus.engine.mcp.protocol.McpResult$Content",
    "allDeclaredConstructors": true,
    "allDeclaredMethods": true
  },
  {
    "name": "com.chorus.engine.mcp.protocol.McpResult$Content$TextContent",
    "allDeclaredConstructors": true,
    "allDeclaredMethods": true
  },
  {
    "name": "com.chorus.engine.mcp.protocol.McpResult$Content$ImageContent",
    "allDeclaredConstructors": true,
    "allDeclaredMethods": true
  },
  {
    "name": "com.chorus.engine.mcp.protocol.McpResult$Content$EmbeddedResourceContent",
    "allDeclaredConstructors": true,
    "allDeclaredMethods": true
  },
  {
    "name": "com.chorus.engine.mcp.protocol.McpResult$CallToolResult",
    "allDeclaredConstructors": true,
    "allDeclaredMethods": true
  },
  {
    "name": "com.chorus.engine.mcp.protocol.McpResult$ReadResourceResult",
    "allDeclaredConstructors": true,
    "allDeclaredMethods": true
  },
  {
    "name": "com.chorus.engine.mcp.protocol.McpResult$GetPromptResult",
    "allDeclaredConstructors": true,
    "allDeclaredMethods": true
  },
  {
    "name": "com.chorus.engine.mcp.protocol.McpResult$ResourceContent",
    "allDeclaredConstructors": true,
    "allDeclaredMethods": true
  },
  {
    "name": "com.chorus.engine.mcp.protocol.McpResult$PromptMessage",
    "allDeclaredConstructors": true,
    "allDeclaredMethods": true
  },
  {
    "name": "com.fasterxml.jackson.datatype.jsr310.JavaTimeModule",
    "allDeclaredConstructors": true
  }
]
```

---

## 15. Revised Deliverables (from Phase 7 Plan)

| Original Deliverable | Status | Revised Requirement |
|---|---|---|
| `native-image.properties` per module | ✅ Correct | One per module that needs native hints (core, llm, a2a, graph, telemetry, mcp, tokenizer) |
| `reflect-config.json` covering AgentEvent, ChatRequest, StreamEvent | ⚠️ Under-scoped | Must cover **all 6 sealed hierarchies** + MCP records + JavaTimeModule |
| Sample compiles with `./gradlew nativeCompile` | ✅ Correct | Add `org.graalvm.buildtools.native` plugin to sample |
| Startup <100ms on linux/amd64 | ✅ Correct | Measure with `time` after smoke test |
| CI job: native-image step in GitHub Actions | ⚠️ Under-scoped | **First** add basic Java CI, **then** add native-image job |

**New deliverable:** `resource-config.json` for tokenizer `.tiktoken` files.

**Removed deliverable:** Caffeine validation (Caffeine not in project).

---

## 16. Conclusion

Chorus Engine's architecture is **unusually native-image-friendly** for a Java framework of this complexity:
- No Netty, no Reactor, no dynamic proxies, no CGLIB
- Clean JDK HttpClient + virtual threads
- Zero ServiceLoader usage
- Jackson is the only heavy reflection user

The work is **mechanical, not architectural**: generate reachability metadata, fix two `Class.forName` probes, register resources, configure the Gradle plugin, and add CI. The sealed-type hierarchies are the largest metadata set (~75 entries in `reflect-config.json`), but it's a one-time cost.

**Risk assessment: LOW.** This is not a "rewrite core abstractions" phase. It's a "configure build tooling" phase.
