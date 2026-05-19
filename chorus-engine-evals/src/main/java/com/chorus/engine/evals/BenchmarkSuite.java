package com.chorus.engine.evals;

import org.jspecify.annotations.NonNull;

import java.util.List;
import java.util.Map;

/**
 * Standard benchmark datasets for common agent capabilities.
 */
public final class BenchmarkSuite {

    private BenchmarkSuite() {}

    /**
     * RAG benchmark: question-answer pairs with ground truth.
     */
    public static @NonNull EvalDataset RAG_BENCHMARK = EvalDataset.of("rag-benchmark", List.of(
        new EvalCase("rag-1",
            "What is the capital of France?",
            "Paris",
            Map.of("category", "geography", "difficulty", "easy")),
        new EvalCase("rag-2",
            "Who wrote '1984'?",
            "George Orwell",
            Map.of("category", "literature", "difficulty", "easy")),
        new EvalCase("rag-3",
            "What is the speed of light in vacuum?",
            "299,792,458 meters per second",
            Map.of("category", "physics", "difficulty", "medium"))
    ));

    /**
     * Tool use benchmark: evaluates tool calling accuracy.
     */
    public static @NonNull EvalDataset TOOL_USE_BENCHMARK = EvalDataset.of("tool-use-benchmark", List.of(
        new EvalCase("tool-1",
            "What is the weather in Tokyo?",
            "weather_lookup(city=Tokyo)",
            Map.of("category", "tool_calling", "tool", "weather_lookup")),
        new EvalCase("tool-2",
            "Search for recent papers on quantum computing",
            "web_search(query=quantum computing)",
            Map.of("category", "tool_calling", "tool", "web_search")),
        new EvalCase("tool-3",
            "Calculate 15 * 23",
            "calculator(expression=15*23)",
            Map.of("category", "tool_calling", "tool", "calculator"))
    ));

    /**
     * Reasoning benchmark: multi-step reasoning tasks.
     */
    public static @NonNull EvalDataset REASONING_BENCHMARK = EvalDataset.of("reasoning-benchmark", List.of(
        new EvalCase("reason-1",
            "If a train travels 60 km in 30 minutes, what is its average speed in km/h?",
            "120",
            Map.of("category", "math", "steps", 2)),
        new EvalCase("reason-2",
            "Alice is older than Bob. Bob is older than Charlie. Is Alice older than Charlie?",
            "Yes",
            Map.of("category", "logic", "steps", 2)),
        new EvalCase("reason-3",
            "A bat and a ball cost $11 in total. The bat costs $10 more than the ball. How much does the ball cost?",
            "0.5",
            Map.of("category", "math", "steps", 3))
    ));
}
