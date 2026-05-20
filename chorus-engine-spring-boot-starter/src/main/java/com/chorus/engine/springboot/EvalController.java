package com.chorus.engine.springboot;

import com.chorus.engine.evals.*;
import com.chorus.engine.llm.LlmClient;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Locale;
import java.util.function.Function;

/**
 * REST endpoints for evaluation operations.
 */
@RestController
@RequestMapping("/api/evals")
public class EvalController {

    private final LlmClient llmClient;

    public EvalController(LlmClient llmClient) {
        this.llmClient = llmClient;
    }

    @PostMapping("/run")
    public ResponseEntity<EvalRunResponse> runEval(@RequestBody EvalRunRequest request) {
        try {
            EvalDataset dataset = EvalDataset.fromJson(request.datasetJson());
            EvalScorer scorer = resolveScorer(request.scorerType());
            EvalRunner runner = new EvalRunner(request.runId() != null ? request.runId() : "api-run");

            Function<String, String> agentRunner = input -> "Agent response for: " + input;

            EvalReport report = runner.run(dataset, agentRunner, scorer);
            return ResponseEntity.ok(new EvalRunResponse(
                report.datasetName(), report.totalCases(), report.passed(),
                report.passRate(), report.avgScore(), report.duration().toMillis(),
                report.results().stream()
                    .map(r -> new EvalResultDto(r.caseId(), r.passed(), r.score(), r.actualOutput()))
                    .toList()
            ));
        } catch (Exception e) {
            return ResponseEntity.internalServerError().body(
                new EvalRunResponse(null, 0, 0, 0.0, 0.0, 0, List.of())
            );
        }
    }

    @GetMapping("/scorers")
    public ResponseEntity<List<String>> listScorers() {
        return ResponseEntity.ok(List.of("exact_match", "semantic_similarity", "llm_judge"));
    }

    @GetMapping("/benchmarks")
    public ResponseEntity<List<String>> listBenchmarks() {
        return ResponseEntity.ok(List.of(
            BenchmarkSuite.RAG_BENCHMARK.name(),
            BenchmarkSuite.TOOL_USE_BENCHMARK.name(),
            BenchmarkSuite.REASONING_BENCHMARK.name()
        ));
    }

    private EvalScorer resolveScorer(String type) {
        return switch (type != null ? type.toLowerCase(Locale.ROOT) : "exact_match") {
            case "llm_judge" -> new LlmJudgeScorer(llmClient, "judge-model", 0.7);
            default -> new ExactMatchScorer();
        };
    }

    public record EvalRunRequest(String runId, String datasetJson, String scorerType) {}
    public record EvalRunResponse(String datasetName, int totalCases, int passed,
                                   double passRate, double avgScore, long durationMs,
                                   List<EvalResultDto> results) {}
    public record EvalResultDto(String caseId, boolean passed, double score, String actualOutput) {}
}
