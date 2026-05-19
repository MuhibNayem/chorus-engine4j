package com.chorus.engine.evals;

import com.chorus.engine.llm.embed.EmbeddingClient;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SemanticSimilarityScorerTest {

    FakeEmbeddingClient fakeClient = new FakeEmbeddingClient();

    @Test
    void scoreWithSimilarTextsReturnsHighScore() {
        float[] vec = {1.0f, 0.0f, 0.0f, 0.0f};
        fakeClient.registerEmbedding("expected", vec);
        fakeClient.registerEmbedding("actual", vec);

        SemanticSimilarityScorer scorer = new SemanticSimilarityScorer(fakeClient, 0.8, "model");
        EvalCase testCase = new EvalCase("1", "input", "expected", Map.of());

        EvalResult result = scorer.score(testCase, "actual");

        assertThat(result.passed()).isTrue();
        assertThat(result.score()).isEqualTo(1.0);
        assertThat(result.caseId()).isEqualTo("1");
        assertThat(result.reasoning()).contains("Cosine similarity: 1.0000");
    }

    @Test
    void scoreWithDissimilarTextsReturnsLowScore() {
        float[] expectedVec = {1.0f, 0.0f, 0.0f, 0.0f};
        float[] actualVec = {0.0f, 1.0f, 0.0f, 0.0f};
        fakeClient.registerEmbedding("expected", expectedVec);
        fakeClient.registerEmbedding("actual", actualVec);

        SemanticSimilarityScorer scorer = new SemanticSimilarityScorer(fakeClient, 0.5, "model");
        EvalCase testCase = new EvalCase("1", "input", "expected", Map.of());

        EvalResult result = scorer.score(testCase, "actual");

        assertThat(result.passed()).isFalse();
        assertThat(result.score()).isEqualTo(0.0);
    }

    @Test
    void boundaryExactlyAtThreshold() {
        // Vectors with cosine similarity exactly 0.8
        // a = [4, 3], b = [4, 0]
        // dot = 16, normA = 5, normB = 4 => sim = 16 / 20 = 0.8
        float[] expectedVec = {4.0f, 3.0f, 0.0f, 0.0f};
        float[] actualVec = {4.0f, 0.0f, 0.0f, 0.0f};
        fakeClient.registerEmbedding("expected", expectedVec);
        fakeClient.registerEmbedding("actual", actualVec);

        SemanticSimilarityScorer scorer = new SemanticSimilarityScorer(fakeClient, 0.8, "model");
        EvalCase testCase = new EvalCase("1", "input", "expected", Map.of());

        EvalResult result = scorer.score(testCase, "actual");

        assertThat(result.passed()).isTrue();
        assertThat(result.score()).isEqualTo(0.8);
    }

    @Test
    void nullEmbeddingClientRejection() {
        assertThatThrownBy(() -> new SemanticSimilarityScorer(null, 0.5, "model"))
            .isInstanceOf(NullPointerException.class);
    }

    @Test
    void nullModelRejection() {
        assertThatThrownBy(() -> new SemanticSimilarityScorer(fakeClient, 0.5, null))
            .isInstanceOf(NullPointerException.class);
    }

    @Test
    void nullTestCaseRejection() {
        SemanticSimilarityScorer scorer = new SemanticSimilarityScorer(fakeClient, 0.5, "model");
        assertThatThrownBy(() -> scorer.score(null, "actual"))
            .isInstanceOf(NullPointerException.class);
    }

    @Test
    void nullActualOutputRejection() {
        SemanticSimilarityScorer scorer = new SemanticSimilarityScorer(fakeClient, 0.5, "model");
        EvalCase testCase = new EvalCase("1", "input", "expected", Map.of());
        assertThatThrownBy(() -> scorer.score(testCase, null))
            .isInstanceOf(NullPointerException.class);
    }

    @Test
    void embeddingFailureForExpectedOutput() {
        fakeClient.setReturnError(true);

        SemanticSimilarityScorer scorer = new SemanticSimilarityScorer(fakeClient, 0.8, "model");
        EvalCase testCase = new EvalCase("1", "input", "expected", Map.of());

        EvalResult result = scorer.score(testCase, "actual");

        assertThat(result.passed()).isFalse();
        assertThat(result.score()).isEqualTo(0.0);
        assertThat(result.reasoning()).contains("Embedding failed for expected output");
    }

    @Test
    void embeddingFailureForActualOutput() {
        // First call succeeds (expected), second fails (actual)
        FakeEmbeddingClient selectiveClient = new FakeEmbeddingClient() {
            private int callCount = 0;

            @Override
            public com.chorus.engine.core.result.Result<float[], EmbeddingClient.EmbeddingError> embed(String text, EmbeddingClient.EmbedOptions options) {
                callCount++;
                if (callCount == 2) {
                    return com.chorus.engine.core.result.Result.err(EmbeddingClient.EmbeddingError.of("EMBED_ERROR", "Actual embedding failed", "fake"));
                }
                return super.embed(text, options);
            }
        };

        SemanticSimilarityScorer scorer = new SemanticSimilarityScorer(selectiveClient, 0.8, "model");
        EvalCase testCase = new EvalCase("1", "input", "expected", Map.of());

        EvalResult result = scorer.score(testCase, "actual");

        assertThat(result.passed()).isFalse();
        assertThat(result.score()).isEqualTo(0.0);
        assertThat(result.reasoning()).contains("Embedding failed for actual output");
    }

    @Test
    void invalidThresholdRejection() {
        assertThatThrownBy(() -> new SemanticSimilarityScorer(fakeClient, -0.1, "model"))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("threshold must be in [0, 1]");

        assertThatThrownBy(() -> new SemanticSimilarityScorer(fakeClient, 1.1, "model"))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("threshold must be in [0, 1]");
    }
}
