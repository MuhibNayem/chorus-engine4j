package com.chorus.engine.rag.query;

import com.chorus.engine.llm.*;
import com.chorus.engine.core.context.Message;
import com.chorus.engine.core.reactive.CancellationToken;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.concurrent.Flow;

import static org.junit.jupiter.api.Assertions.*;

class QueryTransformerTest {

    @Test
    void multiQueryTransformerGeneratesVariants() {
        FakeLlmClient fakeLlm = new FakeLlmClient("1. What is the capital of France?\n2. Which city is France's capital?\n3. France capital city name");
        MultiQueryTransformer transformer = new MultiQueryTransformer(fakeLlm, "gpt-4", 3);

        List<String> queries = transformer.transform("What is the capital of France?",
            new QueryTransformer.TransformContext(List.of(), "general"));

        assertTrue(queries.size() >= 2);
        assertEquals("What is the capital of France?", queries.get(0)); // Original always included
    }

    @Test
    void hydeTransformerGeneratesHypothetical() {
        FakeLlmClient fakeLlm = new FakeLlmClient("Paris is the capital and most populous city of France.");
        HydeTransformer transformer = new HydeTransformer(fakeLlm, "gpt-4");

        List<String> queries = transformer.transform("What is the capital of France?",
            new QueryTransformer.TransformContext(List.of(), "general"));

        assertEquals(1, queries.size());
        assertTrue(queries.get(0).contains("Paris"));
    }

    @Test
    void stepBackTransformerGeneratesBroaderQuery() {
        FakeLlmClient fakeLlm = new FakeLlmClient("What are the capitals of European countries?");
        StepBackTransformer transformer = new StepBackTransformer(fakeLlm, "gpt-4");

        List<String> queries = transformer.transform("What is the capital of France?",
            new QueryTransformer.TransformContext(List.of(), "general"));

        assertEquals(2, queries.size());
        assertEquals("What is the capital of France?", queries.get(1)); // Original is second
    }

    @Test
    void decompositionTransformerBreaksIntoSubQueries() {
        FakeLlmClient fakeLlm = new FakeLlmClient("1. What was Apple's revenue in 2024?\n2. What was Microsoft's revenue in 2024?\n3. How do they compare?");
        DecompositionTransformer transformer = new DecompositionTransformer(fakeLlm, "gpt-4", 3);

        List<String> queries = transformer.transform("Compare Apple and Microsoft revenue in 2024",
            new QueryTransformer.TransformContext(List.of(), "general"));

        assertTrue(queries.size() >= 2);
        assertFalse(queries.get(0).isEmpty());
    }

    // ---- Fake LLM Client ----

    static class FakeLlmClient implements LlmClient {
        private final String responseText;

        FakeLlmClient(String responseText) {
            this.responseText = responseText;
        }

        @Override
        public Flow.Publisher<StreamEvent> stream(ChatRequest request, CancellationToken cancellationToken) {
            throw new UnsupportedOperationException();
        }

        @Override
        public ChatResponse complete(ChatRequest request, CancellationToken cancellationToken) {
            return new ChatResponse(
                "resp-1", "fake", "fake",
                Message.assistant(responseText),
                new com.chorus.engine.core.context.TokenCount(10, 10, "fake"),
                java.time.Duration.ZERO,
                "stop", null, null, Map.of()
            );
        }

        @Override public HealthStatus health() { return HealthStatus.HEALTHY; }
        @Override public String providerName() { return "fake"; }
    }
}
