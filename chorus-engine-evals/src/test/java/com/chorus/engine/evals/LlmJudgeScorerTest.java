package com.chorus.engine.evals;

import com.chorus.engine.core.context.Message;
import com.chorus.engine.core.context.TokenCount;
import com.chorus.engine.llm.ChatResponse;
import com.chorus.engine.llm.LlmClient;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LlmJudgeScorerTest {

    @Test
    void highRatingPasses() {
        FakeLlmClient fakeLlm = new FakeLlmClient();
        fakeLlm.enqueue(buildResponse("8/10. The answer is correct and well explained."));

        LlmJudgeScorer scorer = new LlmJudgeScorer(fakeLlm, "gpt-4o", 0.7);
        EvalCase testCase = new EvalCase("1", "What is 2+2?", "4", Map.of());

        EvalResult result = scorer.score(testCase, "4");

        assertThat(result.passed()).isTrue();
        assertThat(result.score()).isCloseTo(0.8, org.assertj.core.data.Offset.offset(0.01));
        assertThat(result.reasoning()).contains("8");
    }

    @Test
    void lowRatingFails() {
        FakeLlmClient fakeLlm = new FakeLlmClient();
        fakeLlm.enqueue(buildResponse("3/10. The answer is incorrect."));

        LlmJudgeScorer scorer = new LlmJudgeScorer(fakeLlm, "gpt-4o", 0.7);
        EvalCase testCase = new EvalCase("1", "What is 2+2?", "4", Map.of());

        EvalResult result = scorer.score(testCase, "5");

        assertThat(result.passed()).isFalse();
        assertThat(result.score()).isCloseTo(0.3, org.assertj.core.data.Offset.offset(0.01));
    }

    @Test
    void noNumericRatingDefaultsToZero() {
        FakeLlmClient fakeLlm = new FakeLlmClient();
        fakeLlm.enqueue(buildResponse("No rating provided."));

        LlmJudgeScorer scorer = new LlmJudgeScorer(fakeLlm, "gpt-4o", 0.5);
        EvalCase testCase = new EvalCase("1", "Q", "A", Map.of());

        EvalResult result = scorer.score(testCase, "B");

        assertThat(result.passed()).isFalse();
        assertThat(result.score()).isEqualTo(0.0);
    }

    // --- Expanded tests ---

    @Test
    void invalidPassThresholdNegativeRejection() {
        FakeLlmClient fakeLlm = new FakeLlmClient();
        assertThatThrownBy(() -> new LlmJudgeScorer(fakeLlm, "gpt-4o", -0.1))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("passThreshold must be in [0, 1]");
    }

    @Test
    void invalidPassThresholdAboveOneRejection() {
        FakeLlmClient fakeLlm = new FakeLlmClient();
        assertThatThrownBy(() -> new LlmJudgeScorer(fakeLlm, "gpt-4o", 1.1))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("passThreshold must be in [0, 1]");
    }

    @Test
    void llmClientThrowingExceptionPropagates() {
        LlmClient throwingClient = new LlmClient() {
            @Override
            public java.util.concurrent.Flow.Publisher<com.chorus.engine.llm.StreamEvent> stream(com.chorus.engine.llm.ChatRequest request, com.chorus.engine.core.reactive.CancellationToken cancellationToken) {
                throw new UnsupportedOperationException();
            }

            @Override
            public ChatResponse complete(com.chorus.engine.llm.ChatRequest request, com.chorus.engine.core.reactive.CancellationToken cancellationToken) {
                throw new RuntimeException("LLM service unavailable");
            }

            @Override
            public HealthStatus health() {
                return HealthStatus.UNAVAILABLE;
            }

            @Override
            public String providerName() {
                return "throwing";
            }
        };

        LlmJudgeScorer scorer = new LlmJudgeScorer(throwingClient, "gpt-4o", 0.5);
        EvalCase testCase = new EvalCase("1", "Q", "A", Map.of());

        assertThatThrownBy(() -> scorer.score(testCase, "B"))
            .isInstanceOf(RuntimeException.class)
            .hasMessageContaining("LLM service unavailable");
    }

    @Test
    void boundaryRatingZero() {
        FakeLlmClient fakeLlm = new FakeLlmClient();
        fakeLlm.enqueue(buildResponse("0/10. Completely wrong."));

        LlmJudgeScorer scorer = new LlmJudgeScorer(fakeLlm, "gpt-4o", 0.0);
        EvalCase testCase = new EvalCase("1", "Q", "A", Map.of());

        EvalResult result = scorer.score(testCase, "bad");

        assertThat(result.score()).isEqualTo(0.0);
        assertThat(result.passed()).isTrue(); // threshold is 0.0
    }

    @Test
    void boundaryRatingTen() {
        FakeLlmClient fakeLlm = new FakeLlmClient();
        fakeLlm.enqueue(buildResponse("10/10. Perfect answer."));

        LlmJudgeScorer scorer = new LlmJudgeScorer(fakeLlm, "gpt-4o", 1.0);
        EvalCase testCase = new EvalCase("1", "Q", "A", Map.of());

        EvalResult result = scorer.score(testCase, "perfect");

        assertThat(result.score()).isEqualTo(1.0);
        assertThat(result.passed()).isTrue();
    }

    @Test
    void decimalRating() {
        FakeLlmClient fakeLlm = new FakeLlmClient();
        fakeLlm.enqueue(buildResponse("7.5/10. Good but missing some details."));

        LlmJudgeScorer scorer = new LlmJudgeScorer(fakeLlm, "gpt-4o", 0.7);
        EvalCase testCase = new EvalCase("1", "Q", "A", Map.of());

        EvalResult result = scorer.score(testCase, "mostly correct");

        assertThat(result.score()).isCloseTo(0.75, org.assertj.core.data.Offset.offset(0.001));
        assertThat(result.passed()).isTrue(); // 0.75 >= 0.7
    }

    private static ChatResponse buildResponse(String content) {
        return new ChatResponse(
            "id-1",
            "gpt-4o",
            "fake",
            Message.assistant(content),
            new TokenCount(10, 5, "fake"),
            Duration.ZERO,
            "stop",
            null,
            null,
            Map.of()
        );
    }
}
