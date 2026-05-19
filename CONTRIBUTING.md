# Contributing to Chorus Engine

Thank you for your interest in contributing! This document covers build, test, and submission guidelines.

## Development Setup

### Prerequisites

- Java 25 (OpenJDK or Oracle JDK)
- Gradle 9.1.0+
- Git

### Clone and Build

```bash
git clone https://github.com/chorus-engine/chorus-engine.git
cd chorus-engine/chorus-engine-java
./gradlew compileJava
```

### Run Tests

```bash
./gradlew test
```

All 1,015 tests must pass. The build enforces zero test failures.

### IDE Setup

Import as a Gradle project. Ensure your IDE is configured for Java 25 with `--enable-preview`.

## Project Structure

```
chorus-engine-java/
├── chorus-engine-core/           # Foundation types (zero deps)
├── chorus-engine-tokenizer/      # Token counting
├── chorus-engine-llm/            # LLM client abstraction
├── chorus-engine-agent/          # ReAct agent loop
├── chorus-engine-graph/          # DAG workflows
├── chorus-engine-swarm/          # Multi-agent orchestration
├── chorus-engine-harness/        # Evaluation harness
├── chorus-engine-tools/          # Tool registry
├── chorus-engine-guardrails/     # Safety guardrails
├── chorus-engine-skills/         # Skill routing
├── chorus-engine-telemetry/      # Observability
├── chorus-engine-mcp/            # MCP protocol
├── chorus-engine-a2a/            # A2A protocol
├── chorus-engine-evals/          # Evaluation framework
├── chorus-engine-memory/         # Memory system
├── chorus-engine-rag/            # RAG pipelines
├── chorus-engine-spring-boot-starter/  # Spring Boot auto-config
└── chorus-engine-spring-boot-sample/   # Demo application
```

## Making Changes

### Code Style

- Use `record` for immutable data
- Use `sealed interface` for sum types
- Annotate nullability with `@NonNull` / `@Nullable`
- No Lombok — hand-write all types
- No Mockito — use hand-written fakes

### Adding a New Module

1. Create directory: `chorus-engine-newmodule/`
2. Add `build.gradle.kts` with dependencies
3. Add to `settings.gradle.kts`
4. Create `src/main/java/com/chorus/engine/newmodule/` with `package-info.java`
5. Create `README.md` following the existing template
6. Add tests in `src/test/java/`
7. Wire into `ChorusAutoConfiguration.java` if Spring Boot integration is needed

### Testing Requirements

- Every public API must have tests
- Use AssertJ + JUnit 5
- Hand-written fakes only — no mocking frameworks
- Test JVM args: `--enable-preview --add-modules jdk.incubator.vector`

### Documentation Requirements

- New public classes need Javadoc
- New modules need a `README.md`
- Update `docs/GUIDE.md` if adding user-facing features
- Update `CHANGELOG.md` under `[Unreleased]`

## Submitting Changes

1. Create a feature branch: `git checkout -b feat/my-feature`
2. Make your changes with tests and docs
3. Ensure `./gradlew test` passes
4. Commit with conventional commit format:
   ```
   feat(module): add new feature
   fix(module): fix bug
   docs(module): update documentation
   test(module): add tests
   refactor(module): code change without behavior change
   ```
5. Push and open a Pull Request

## Release Process

1. Update version in `build.gradle.kts`
2. Update `CHANGELOG.md` with release date
3. Tag: `git tag v0.1.0`
4. Push tag: `git push origin v0.1.0`
5. GitHub Actions publishes to Maven Central automatically

## Questions?

- Open an issue: https://github.com/chorus-engine/chorus-engine/issues
- Start a discussion: https://github.com/chorus-engine/chorus-engine/discussions
