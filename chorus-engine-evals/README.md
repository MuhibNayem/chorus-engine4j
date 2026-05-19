# chorus-engine-evals

Evaluation framework for agentic systems with LLM-as-judge, semantic similarity, and parallel execution.

## Purpose

The `evals` module provides the tooling to measure agent quality: task success rates, response accuracy, latency, cost efficiency, and safety. It supports both automated metrics (semantic similarity) and LLM-based judgment.

## Key APIs

| Class | Purpose |
|---|---|
| `EvalRunner` | Run a suite of evaluation cases sequentially or in parallel. Produces an `EvalReport`. |
| `ParallelEvalRunner` | Execute evaluations in parallel using `StructuredTaskScope`. |
| `EvalCase` | Single test case: input, expected output, evaluation criteria, metadata. |
| `EvalReport` | Aggregated results: success rate, average latency, cost per case, per-metric breakdowns. |
| `LlmJudgeScorer` | Uses a secondary LLM to score outputs against criteria (1-5 scale, pass/fail, custom rubric). |
| `SemanticSimilarityScorer` | Measures cosine similarity between expected and actual output embeddings. |
| `EvalDataset` | Load evaluation datasets from JSON files. Supports train/test splits. |
| `EvalReportExporter` | Export reports to JSON, HTML, or Markdown formats. |

## Evaluation Metrics

| Metric | Type | Use Case |
|---|---|---|
| `exact_match` | Binary | Output equals expected exactly |
| `semantic_similarity` | 0.0-1.0 | Embedding cosine similarity |
| `llm_judge` | 1-5 or pass/fail | Nuanced quality assessment |
| `latency_ms` | Numeric | Response time |
| `token_cost` | Numeric | API cost per evaluation |
| `tool_accuracy` | 0.0-1.0 | Correct tool selected and parameters valid |

## Usage Example

```java
import com.chorus.engine.evals.*;

// Define evaluation cases
List<EvalCase> cases = List.of(
    EvalCase.builder()
        .input("What is the capital of France?")
        .expectedOutput("Paris")
        .criteria(Criteria.exactMatch())
        .build(),
    EvalCase.builder()
        .input("Summarize: The quick brown fox...")
        .expectedOutput("A fox jumps over a lazy dog.")
        .criteria(Criteria.semanticSimilarity(0.85))
        .build()
);

// Run evaluations
EvalRunner runner = new EvalRunner(agent, eventBus);
EvalReport report = runner.run(cases);

// LLM-as-judge
LlmJudgeScorer judge = new LlmJudgeScorer(judgeClient,
    "Rate this response for accuracy and clarity (1-5).");
EvalReport judgedReport = runner.run(cases, List.of(judge));

// Export
EvalReportExporter exporter = new EvalReportExporter();
exporter.toJson(report, Path.of("eval-report.json"));
```

## Parallel Execution

```java
ParallelEvalRunner parallelRunner = new ParallelEvalRunner(agent, eventBus, 8);
EvalReport report = parallelRunner.run(cases); // 8 concurrent evaluations
```

## Dependencies

- `chorus-engine-core`
- `chorus-engine-llm`
- `chorus-engine-agent`
- `chorus-engine-rag`
- Jackson

## Thread Safety

`EvalRunner` is thread-safe. `ParallelEvalRunner` uses virtual threads for concurrent execution.
