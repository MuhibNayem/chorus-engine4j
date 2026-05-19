package com.chorus.engine.guardrails.tier;

import com.chorus.engine.core.result.Result;
import com.chorus.engine.guardrails.Guardrail;
import com.chorus.engine.guardrails.GuardrailResult;
import com.chorus.engine.llm.embed.EmbeddingClient;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.*;

class EmbeddingSimilarityGuardrailTest {

    @Test
    void evaluate_similarForbiddenEmbedding_blocks() {
        float[] forbidden = {1.0f, 0.0f, 0.0f};
        FakeEmbeddingClient fake = new FakeEmbeddingClient(forbidden);
        EmbeddingSimilarityGuardrail guardrail = new EmbeddingSimilarityGuardrail(
            "forbidden-sim", List.of(forbidden), 0.8, fake, "model", 100
        );

        GuardrailResult result = guardrail.evaluate("sensitive input",
            new Guardrail.GuardrailContext("r1", "a1", "input"));

        assertThat(result.allowed()).isFalse();
        assertThat(result.action()).isEqualTo(GuardrailResult.Action.BLOCK);
        assertThat(result.guardrailName()).isEqualTo("forbidden-sim");
        assertThat(result.tier()).isEqualTo(2);
    }

    @Test
    void evaluate_dissimilarInput_allows() {
        float[] forbidden = {1.0f, 0.0f, 0.0f};
        float[] input = {0.0f, 1.0f, 0.0f};
        FakeEmbeddingClient fake = new FakeEmbeddingClient(input);
        EmbeddingSimilarityGuardrail guardrail = new EmbeddingSimilarityGuardrail(
            "forbidden-sim", List.of(forbidden), 0.8, fake, "model", 100
        );

        GuardrailResult result = guardrail.evaluate("benign input",
            new Guardrail.GuardrailContext("r1", "a1", "input"));

        assertThat(result.allowed()).isTrue();
        assertThat(result.action()).isEqualTo(GuardrailResult.Action.ALLOW);
    }

    @Test
    void evaluate_cacheHit_avoidsSecondEmbedCall() {
        float[] forbidden = {1.0f, 0.0f, 0.0f};
        FakeEmbeddingClient fake = new FakeEmbeddingClient(forbidden);
        EmbeddingSimilarityGuardrail guardrail = new EmbeddingSimilarityGuardrail(
            "forbidden-sim", List.of(forbidden), 0.8, fake, "model", 100
        );

        guardrail.evaluate("same-input", new Guardrail.GuardrailContext("r1", "a1", "input"));
        guardrail.evaluate("same-input", new Guardrail.GuardrailContext("r2", "a2", "input"));

        assertThat(fake.embedCallCount).isEqualTo(1);
    }

    @Test
    void evaluate_thresholdBoundary_exactlyAtThreshold() {
        float[] forbidden = {1.0f, 0.0f, 0.0f};
        // cosine similarity with {1,0,0} is exactly 0.8
        float[] input = {0.8f, 0.6f, 0.0f};
        FakeEmbeddingClient fake = new FakeEmbeddingClient(input);
        EmbeddingSimilarityGuardrail guardrail = new EmbeddingSimilarityGuardrail(
            "forbidden-sim", List.of(forbidden), 0.8, fake, "model", 100
        );

        GuardrailResult result = guardrail.evaluate("boundary input",
            new Guardrail.GuardrailContext("r1", "a1", "input"));

        // maxSim >= threshold => block
        assertThat(result.allowed()).isFalse();
    }

    @Test
    void evaluate_thresholdBoundary_justBelowThreshold() {
        float[] forbidden = {1.0f, 0.0f, 0.0f};
        // cosine similarity with {1,0,0} is ~0.799
        float[] input = {0.799f, 0.601f, 0.0f};
        FakeEmbeddingClient fake = new FakeEmbeddingClient(input);
        EmbeddingSimilarityGuardrail guardrail = new EmbeddingSimilarityGuardrail(
            "forbidden-sim", List.of(forbidden), 0.8, fake, "model", 100
        );

        GuardrailResult result = guardrail.evaluate("just below boundary",
            new Guardrail.GuardrailContext("r1", "a1", "input"));

        assertThat(result.allowed()).isTrue();
    }

    @Test
    void evaluate_embeddingFailure_allows() {
        float[] forbidden = {1.0f, 0.0f, 0.0f};
        FakeEmbeddingClient fake = new FakeEmbeddingClient(null);
        EmbeddingSimilarityGuardrail guardrail = new EmbeddingSimilarityGuardrail(
            "forbidden-sim", List.of(forbidden), 0.8, fake, "model", 100
        );

        GuardrailResult result = guardrail.evaluate("any input",
            new Guardrail.GuardrailContext("r1", "a1", "input"));

        assertThat(result.allowed()).isTrue();
    }

    @Test
    void evaluate_nullRejection() {
        float[] forbidden = {1.0f, 0.0f, 0.0f};
        FakeEmbeddingClient fake = new FakeEmbeddingClient(forbidden);
        EmbeddingSimilarityGuardrail guardrail = new EmbeddingSimilarityGuardrail(
            "forbidden-sim", List.of(forbidden), 0.8, fake, "model", 100
        );

        assertThatNullPointerException()
            .isThrownBy(() -> guardrail.evaluate(null, new Guardrail.GuardrailContext("r1", "a1", "input")));
        assertThatNullPointerException()
            .isThrownBy(() -> guardrail.evaluate("input", null));
    }

    // ---- helpers ----

    static final class FakeEmbeddingClient implements EmbeddingClient {
        private final float[] embedding;
        int embedCallCount = 0;

        FakeEmbeddingClient(float[] embedding) {
            this.embedding = embedding;
        }

        @Override
        public Result<float[], EmbeddingError> embed(String text, EmbedOptions options) {
            embedCallCount++;
            if (embedding == null) {
                return Result.err(EmbeddingError.of("FAIL", "Simulated failure", "fake"));
            }
            return Result.ok(embedding);
        }

        @Override
        public Result<List<float[]>, EmbeddingError> embedBatch(List<String> texts, EmbedOptions options) {
            return Result.ok(texts.stream().map(t -> embedding).toList());
        }

        @Override
        public String providerName() {
            return "fake";
        }

        @Override
        public String modelName() {
            return "fake-model";
        }

        @Override
        public int nativeDimensions() {
            return embedding != null ? embedding.length : 3;
        }

        @Override
        public boolean isLocal() {
            return true;
        }

        @Override
        public HealthStatus health() {
            return HealthStatus.HEALTHY;
        }
    }
}
