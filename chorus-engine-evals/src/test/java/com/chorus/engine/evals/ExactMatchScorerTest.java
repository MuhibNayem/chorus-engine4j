package com.chorus.engine.evals;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class ExactMatchScorerTest {

    @Test
    void exactMatchPasses() {
        ExactMatchScorer scorer = new ExactMatchScorer();
        EvalCase testCase = new EvalCase("1", "hello", "world", Map.of());

        EvalResult result = scorer.score(testCase, "world");

        assertTrue(result.passed());
        assertEquals(1.0, result.score(), 0.001);
        assertEquals("1", result.caseId());
    }

    @Test
    void mismatchFails() {
        ExactMatchScorer scorer = new ExactMatchScorer();
        EvalCase testCase = new EvalCase("1", "hello", "world", Map.of());

        EvalResult result = scorer.score(testCase, "earth");

        assertFalse(result.passed());
        assertEquals(0.0, result.score(), 0.001);
        assertNotNull(result.reasoning());
    }

    @Test
    void ignoreCaseMatch() {
        ExactMatchScorer scorer = new ExactMatchScorer(true, true);
        EvalCase testCase = new EvalCase("1", "hello", "World", Map.of());

        EvalResult result = scorer.score(testCase, "world");

        assertTrue(result.passed());
    }

    @Test
    void trimWhitespaceEnabled() {
        ExactMatchScorer scorer = new ExactMatchScorer(false, true);
        EvalCase testCase = new EvalCase("1", "hello", "world", Map.of());

        EvalResult result = scorer.score(testCase, "  world  ");

        assertTrue(result.passed());
    }

    @Test
    void trimWhitespaceDisabled() {
        ExactMatchScorer scorer = new ExactMatchScorer(false, false);
        EvalCase testCase = new EvalCase("1", "hello", "world", Map.of());

        EvalResult result = scorer.score(testCase, "  world  ");

        assertFalse(result.passed());
    }
}
