# AGENTS.md — Coding Conventions for Chorus Engine Contributors

This document governs the `chorus-engine-java/` directory and all subdirectories.

## Technology Stack

- **Java 25** with `--enable-preview` (Structured Concurrency, Stable Values)
- **Gradle 9.1.0** with Kotlin DSL
- **Spring Boot 4.0.0** (no Spring AI dependency)
- **Jackson 2.18.0** for JSON
- **JUnit 5.11.0** + **AssertJ** for testing
- **Zero Lombok** — all records and classes are hand-written
- **Zero Mockito** — use hand-written fakes and stubs only

## Code Style

### Types
- Prefer `record` for immutable data carriers
- Use `sealed interface` for sum types (e.g., `AgentEvent`, `StreamEvent`, `Result`)
- Use `org.jspecify.annotations.NonNull` and `@Nullable` for null safety
- Place `@NonNull` on generic return types as `Flow.@NonNull Publisher<X>`
- Place `@NonNull` on inner class references as `WorkerResult.@NonNull VerificationSummary`

### Concurrency
- Use JDK `HttpClient` — **no Netty, no Reactor, no Project Reactor**
- Use `java.util.concurrent.Flow.Publisher` for streaming
- Use `FlowCollector` instead of manual `CountDownLatch` + `Flow.Subscriber`
- Use virtual threads for I/O-bound parallelism (`StructuredTaskScope` where appropriate)
- `ExecutorService` should be managed (created in constructor, shut down in `close()`)

### Error Handling
- Use `Result<T, E>` instead of throwing checked exceptions
- `Result.ok(null)` throws NPE — use `new Result.Ok<>(null)` for `Result<Void, E>`
- Propagate cancellation via `CancellationToken`

### Testing
- Hand-written fakes only — **no Mockito, no PowerMock, no mocking frameworks**
- AssertJ + JUnit 5
- Test JVM args: `--enable-preview --add-modules jdk.incubator.vector`
- `maxHeapSize = "2g"`

### JSON / Jackson
- Use `ObjectMapper` passed from caller — don't create new instances in library code
- Register `JavaTimeModule` for `Instant` serialization
- Use `@JsonTypeInfo` + `@JsonSubTypes` for explicit polymorphism
- Use custom `StdDeserializer` when structural inspection is needed (e.g., `McpMessage`)

### Spring Boot
- All beans must be `@ConditionalOnMissingBean`
- Use `@ConditionalOnProperty` for opt-in features
- Use `@ConditionalOnClass(name = "...")` for optional dependencies
- `ChorusProperties` is the single source of truth for all configuration

## Module Boundaries

- `core` has **zero** external runtime dependencies
- Each module depends only on what it needs (see dependency graph in `docs/GUIDE.md`)
- `spring-boot-starter` is the only module that brings in Spring dependencies

## Native Image Considerations

- Avoid `Class.forName` — use try-catch around direct instantiation
- Avoid `ServiceLoader` — not used in this codebase
- Avoid dynamic proxies, CGLIB, ByteBuddy
- Register all sealed types in `META-INF/native-image/reflect-config.json`
- Register resource files in `META-INF/native-image/resource-config.json`

## Commit Messages

Use conventional commits:
```
feat(module): add new feature
fix(module): fix bug
docs(module): update documentation
test(module): add tests
refactor(module): code change without behavior change
```

## Before Submitting

1. `./gradlew test` passes (1,015 tests, 0 failures)
2. `./gradlew compileJava` passes
3. New public APIs have Javadoc
4. New modules have a `README.md`
