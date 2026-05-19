package com.chorus.engine.evals;

import com.chorus.engine.core.context.Message;
import com.chorus.engine.core.context.TokenCount;
import com.chorus.engine.llm.ChatResponse;
import com.chorus.engine.llm.LlmClient;
// FakeLlmClient is in the same test package
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class LlmJudgeScorerTest {

    @Test
    void highRatingPasses() {
        FakeLlmClient fakeLlm = new FakeLlmClient();
        fakeLlm.enqueue(buildResponse("8/10. The answer is correct and well explained."));

        LlmJudgeScorer scorer = new LlmJudgeScorer(fakeLlm, "gpt-4o", 0.7);
        EvalCase testCase = new EvalCase("1", "What is 2+2?", "4", Map.of());

        EvalResult result = scorer.score(testCase, "4");

        assertTrue(result.passed());
        assertEquals(0.8, result.score(), 0.01);
        assertTrue(result.reasoning().contains("8"));
    }

    @Test
    void lowRatingFails() {
        FakeLlmClient fakeLlm = new FakeLlmClient();
        fakeLlm.enqueue(buildResponse("3/10. The answer is incorrect."));

        LlmJudgeScorer scorer = new LlmJudgeScorer(fakeLlm, "gpt-4o", 0.7);
        EvalCase testCase = new EvalCase("1", "What is 2+2?", "4", Map.of());

        EvalResult result = scorer.score(testCase, "5");

        assertFalse(result.passed());
        assertEquals(0.3, result.score(), 0.01);
    }

    @Test
    void noNumericRatingDefaultsToZero() {
        FakeLlmClient fakeLlm = new FakeLlmClient();
        fakeLlm.enqueue(buildResponse("No rating provided."));

        LlmJudgeScorer scorer = new LlmJudgeScorer(fakeLlm, "gpt-4o", 0.5);
        EvalCase testCase = new EvalCase("1", "Q", "A", Map.of());

        EvalResult result = scorer.score(testCase, "B");

        assertFalse(result.passed());
        assertEquals(0.0, result.score(), 0.01);
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
