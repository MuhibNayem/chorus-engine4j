package com.chorus.engine.evals;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class BenchmarkSuiteTest {

    @Test
    void ragBenchmarkExistsAndIsNonEmpty() {
        EvalDataset dataset = BenchmarkSuite.RAG_BENCHMARK;

        assertThat(dataset).isNotNull();
        assertThat(dataset.name()).isEqualTo("rag-benchmark");
        assertThat(dataset.cases()).isNotEmpty();
        assertThat(dataset.cases().size()).isEqualTo(3);
    }

    @Test
    void toolUseBenchmarkExistsAndIsNonEmpty() {
        EvalDataset dataset = BenchmarkSuite.TOOL_USE_BENCHMARK;

        assertThat(dataset).isNotNull();
        assertThat(dataset.name()).isEqualTo("tool-use-benchmark");
        assertThat(dataset.cases()).isNotEmpty();
        assertThat(dataset.cases().size()).isEqualTo(3);
    }

    @Test
    void reasoningBenchmarkExistsAndIsNonEmpty() {
        EvalDataset dataset = BenchmarkSuite.REASONING_BENCHMARK;

        assertThat(dataset).isNotNull();
        assertThat(dataset.name()).isEqualTo("reasoning-benchmark");
        assertThat(dataset.cases()).isNotEmpty();
        assertThat(dataset.cases().size()).isEqualTo(3);
    }

    @Test
    void ragBenchmarkStructure() {
        EvalCase first = BenchmarkSuite.RAG_BENCHMARK.cases().get(0);

        assertThat(first.id()).isEqualTo("rag-1");
        assertThat(first.input()).isEqualTo("What is the capital of France?");
        assertThat(first.expectedOutput()).isEqualTo("Paris");
        assertThat(first.metadata()).containsEntry("category", "geography");
        assertThat(first.metadata()).containsEntry("difficulty", "easy");
    }

    @Test
    void toolUseBenchmarkStructure() {
        EvalCase second = BenchmarkSuite.TOOL_USE_BENCHMARK.cases().get(1);

        assertThat(second.id()).isEqualTo("tool-2");
        assertThat(second.input()).isEqualTo("Search for recent papers on quantum computing");
        assertThat(second.expectedOutput()).isEqualTo("web_search(query=quantum computing)");
        assertThat(second.metadata()).containsEntry("category", "tool_calling");
        assertThat(second.metadata()).containsEntry("tool", "web_search");
    }

    @Test
    void reasoningBenchmarkStructure() {
        EvalCase third = BenchmarkSuite.REASONING_BENCHMARK.cases().get(2);

        assertThat(third.id()).isEqualTo("reason-3");
        assertThat(third.input()).contains("bat and a ball");
        assertThat(third.expectedOutput()).isEqualTo("0.5");
        assertThat(third.metadata()).containsEntry("category", "math");
        assertThat(third.metadata()).containsEntry("steps", 3);
    }
}
