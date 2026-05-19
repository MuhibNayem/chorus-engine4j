# chorus-engine-harness

Evaluation harness for agentic systems with semantic task routing, trajectory logging, and project memory.

## Purpose

The `harness` module provides the infrastructure to test, evaluate, and benchmark agentic systems at scale. It includes a semantic task router that assigns tasks to the right evaluation pipeline, a trajectory logger for replay and debugging, and project memory for accumulating knowledge across evaluation runs.

## Key APIs

| Class | Purpose |
|---|---|
| `HarnessEngine` | Main entry point. Loads test suites, routes tasks, executes workers, collects results. |
| `SemanticTaskRouter` | Routes incoming tasks to the best evaluation pipeline based on semantic similarity of task descriptions. Not keyword matching — actual embedding-based similarity. |
| `TaskOrchestrator` | Manages worker execution: parallel vs pipeline modes, dependency resolution, artifact passing. |
| `TrajectoryLogger` | Records every step of an agent run for later replay, debugging, and analysis. Immutable trajectory format. |
| `ProjectMemoryStore` | Persistent store for evaluation artifacts, benchmarks, and learned patterns across runs. |

## Semantic Routing

The router embeds task descriptions and compares them to route label embeddings using cosine similarity. It supports:

- **Multi-label scoring**: A task can match multiple routes with confidence scores
- **Hybrid fallback**: If no route exceeds the threshold, falls back to a default embedder
- **Per-route thresholds**: Different confidence thresholds per evaluation type

## Route Labels

| Label | Capability | Example Task |
|---|---|---|
| `code-generation` | Generate code from specs | "Write a Java method that sorts a list" |
| `code-review` | Review code for bugs | "Find the bug in this quicksort implementation" |
| `question-answering` | Answer factual questions | "What is the time complexity of Dijkstra's algorithm?" |
| `summarization` | Summarize long texts | "Summarize this 10-page research paper" |
| `classification` | Categorize inputs | "Classify these support tickets by urgency" |
| `extraction` | Extract structured data | "Extract all dates and names from this contract" |
| `translation` | Translate between languages | "Translate this paragraph to Japanese" |

## Usage Example

```java
import com.chorus.engine.harness.HarnessEngine;
import com.chorus.engine.harness.SemanticTaskRouter;

// Configure routes
SemanticTaskRouter router = SemanticTaskRouter.builder()
    .embeddingClient(embeddingClient)
    .route("code-generation", "Generate code from natural language specifications")
    .route("code-review", "Review code for bugs, security issues, and style")
    .defaultThreshold(0.75)
    .build();

// Run harness
HarnessEngine harness = HarnessEngine.builder()
    .router(router)
    .llmClient(llmClient)
    .build();

HarnessResult result = harness.evaluate(
    Task.builder()
        .description("Write a REST endpoint for user registration")
        .expectedOutput("Spring Boot controller with validation")
        .build()
);

System.out.println("Score: " + result.score());
System.out.println("Route: " + result.matchedRoute());
```

## Dependencies

- `chorus-engine-core`
- `chorus-engine-llm`
- `chorus-engine-agent`
- `chorus-engine-swarm`
- `chorus-engine-skills`
- `chorus-engine-tools`
- `chorus-engine-memory`
- `chorus-engine-telemetry`
- Jackson

## Thread Safety

`HarnessEngine` and `SemanticTaskRouter` are thread-safe. `TrajectoryLogger` produces immutable trajectories.
