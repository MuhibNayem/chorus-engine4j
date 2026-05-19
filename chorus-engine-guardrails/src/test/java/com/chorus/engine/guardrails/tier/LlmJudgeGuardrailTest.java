package com.chorus.engine.guardrails.tier;

import com.chorus.engine.guardrails.Guardrail;
import com.chorus.engine.guardrails.GuardrailResult;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.*;

class LlmJudgeGuardrailTest {

    @Test
    void evaluate_policyViolation_blocks() {
        FakeLlmJudgeClient fake = new FakeLlmJudgeClient(true, 0.95, "Contains PII");
        LlmJudgeGuardrail guardrail = new LlmJudgeGuardrail(
            "pii-check", "No PII allowed", 0.9, fake, 100
        );

        GuardrailResult result = guardrail.evaluate("My SSN is 123-45-6789",
            new Guardrail.GuardrailContext("r1", "a1", "input"));

        assertThat(result.allowed()).isFalse();
        assertThat(result.action()).isEqualTo(GuardrailResult.Action.BLOCK);
        assertThat(result.confidence()).isEqualTo(0.95);
        assertThat(result.matchedContent()).isEqualTo("Contains PII");
    }

    @Test
    void evaluate_safeInput_allows() {
        FakeLlmJudgeClient fake = new FakeLlmJudgeClient(false, 0.1, "Clean");
        LlmJudgeGuardrail guardrail = new LlmJudgeGuardrail(
            "pii-check", "No PII allowed", 0.9, fake, 100
        );

        GuardrailResult result = guardrail.evaluate("Hello world",
            new Guardrail.GuardrailContext("r1", "a1", "input"));

        assertThat(result.allowed()).isTrue();
        assertThat(result.action()).isEqualTo(GuardrailResult.Action.ALLOW);
    }

    @Test
    void evaluate_belowBlockThreshold_allows() {
        FakeLlmJudgeClient fake = new FakeLlmJudgeClient(true, 0.85, "Mild violation");
        LlmJudgeGuardrail guardrail = new LlmJudgeGuardrail(
            "pii-check", "No PII allowed", 0.9, fake, 100
        );

        GuardrailResult result = guardrail.evaluate("borderline input",
            new Guardrail.GuardrailContext("r1", "a1", "input"));

        // violatesPolicy=true but confidence < blockThreshold => allow
        assertThat(result.allowed()).isTrue();
    }

    @Test
    void evaluate_cacheHit_avoidsSecondJudgeCall() {
        FakeLlmJudgeClient fake = new FakeLlmJudgeClient(false, 0.1, "Clean");
        LlmJudgeGuardrail guardrail = new LlmJudgeGuardrail(
            "pii-check", "No PII allowed", 0.9, fake, 100
        );

        guardrail.evaluate("same-input", new Guardrail.GuardrailContext("r1", "a1", "input"));
        guardrail.evaluate("same-input", new Guardrail.GuardrailContext("r2", "a2", "input"));

        assertThat(fake.callCount.get()).isEqualTo(1);
    }

    @Test
    void evaluate_failOpen_onException() {
        FakeLlmJudgeClient fake = new FakeLlmJudgeClient(new RuntimeException("LLM down"));
        LlmJudgeGuardrail guardrail = new LlmJudgeGuardrail(
            "pii-check", "No PII allowed", 0.9, fake, 100
        );

        GuardrailResult result = guardrail.evaluate("input",
            new Guardrail.GuardrailContext("r1", "a1", "input"));

        assertThat(result.allowed()).isTrue();
        assertThat(result.action()).isEqualTo(GuardrailResult.Action.ALLOW);
    }

    @Test
    void guardrailMetadata_isCorrect() {
        FakeLlmJudgeClient fake = new FakeLlmJudgeClient(false, 0.1, "Clean");
        LlmJudgeGuardrail guardrail = new LlmJudgeGuardrail(
            "pii-check", "No PII allowed", 0.9, fake, 100
        );

        assertThat(guardrail.name()).isEqualTo("pii-check");
        assertThat(guardrail.tier()).isEqualTo(3);
        assertThat(guardrail.supportsOutputValidation()).isFalse();
    }

    @Test
    void evaluate_nullRejection() {
        FakeLlmJudgeClient fake = new FakeLlmJudgeClient(false, 0.1, "Clean");
        LlmJudgeGuardrail guardrail = new LlmJudgeGuardrail(
            "pii-check", "No PII allowed", 0.9, fake, 100
        );

        assertThatNullPointerException()
            .isThrownBy(() -> guardrail.evaluate(null, new Guardrail.GuardrailContext("r1", "a1", "input")));
        assertThatNullPointerException()
            .isThrownBy(() -> guardrail.evaluate("input", null));
    }

    // ---- helpers ----

    static final class FakeLlmJudgeClient implements LlmJudgeGuardrail.LlmJudgeClient {
        private final boolean violates;
        private final double confidence;
        private final String reasoning;
        private final RuntimeException exception;
        final AtomicInteger callCount = new AtomicInteger(0);

        FakeLlmJudgeClient(boolean violates, double confidence, String reasoning) {
            this.violates = violates;
            this.confidence = confidence;
            this.reasoning = reasoning;
            this.exception = null;
        }

        FakeLlmJudgeClient(RuntimeException exception) {
            this.violates = false;
            this.confidence = 0.0;
            this.reasoning = "";
            this.exception = exception;
        }

        @Override
        public JudgeResult judge(String input, String policy) {
            callCount.incrementAndGet();
            if (exception != null) {
                throw exception;
            }
            return new JudgeResult(violates, confidence, reasoning);
        }
    }
}
