package com.chorus.engine.rag.streaming;

import com.chorus.engine.core.context.Message;
import com.chorus.engine.core.context.TokenCount;
import com.chorus.engine.core.reactive.CancellationToken;
import com.chorus.engine.llm.ChatRequest;
import com.chorus.engine.llm.ChatResponse;
import com.chorus.engine.llm.LlmClient;
import com.chorus.engine.llm.StreamEvent;
import com.chorus.engine.rag.document.Chunk;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.Flow;

import static org.assertj.core.api.Assertions.*;

class PostGenerationVerifierTest {

    @Test
    void verify_withConsistentAnswer_returnsVerified() {
        FakeLlmClient fake = new FakeLlmClient();
        fake.enqueue(verdictResponse("VERIFIED", 0.95, "None", "Answer is consistent"));
        PostGenerationVerifier verifier = new PostGenerationVerifier(fake, "cheap-model", 0.8);

        var result = verifier.verify("q", "a", List.of(chunk("c1", "text")), CancellationToken.create());

        assertThat(result.type()).isEqualTo(PostGenerationVerifier.ResultType.VERIFIED);
        assertThat(result.confidence()).isEqualTo(0.95);
        assertThat(result.isActionable()).isFalse();
        assertThat(result.reasoning()).isEqualTo("Answer is consistent");
    }

    @Test
    void verify_withInconsistentAnswer_returnsCorrected() {
        FakeLlmClient fake = new FakeLlmClient();
        fake.enqueue(verdictResponse("CONTRADICTED", 0.95, "The correct answer is 42", "Answer contradicts source"));
        PostGenerationVerifier verifier = new PostGenerationVerifier(fake, "cheap-model", 0.8);

        var result = verifier.verify("q", "a", List.of(chunk("c1", "text")), CancellationToken.create());

        assertThat(result.type()).isEqualTo(PostGenerationVerifier.ResultType.CONTRADICTED);
        assertThat(result.correction()).isEqualTo("The correct answer is 42");
        assertThat(result.isActionable()).isTrue();
    }

    @Test
    void verify_withNeedsCorrection_returnsCorrected() {
        FakeLlmClient fake = new FakeLlmClient();
        fake.enqueue(verdictResponse("NEEDS_CORRECTION", 0.85, "Minor fix needed", "Missing detail"));
        PostGenerationVerifier verifier = new PostGenerationVerifier(fake, "cheap-model", 0.8);

        var result = verifier.verify("q", "a", List.of(chunk("c1", "text")), CancellationToken.create());

        assertThat(result.type()).isEqualTo(PostGenerationVerifier.ResultType.NEEDS_CORRECTION);
        assertThat(result.correction()).isEqualTo("Minor fix needed");
        assertThat(result.isActionable()).isTrue();
    }

    @Test
    void verify_boundaryConfidence_exactlyAtThreshold() {
        FakeLlmClient fake = new FakeLlmClient();
        fake.enqueue(verdictResponse("CONTRADICTED", 0.80, "Correction at boundary", "Boundary test"));
        PostGenerationVerifier verifier = new PostGenerationVerifier(fake, "cheap-model", 0.8);

        var result = verifier.verify("q", "a", List.of(chunk("c1", "text")), CancellationToken.create());

        assertThat(result.type()).isEqualTo(PostGenerationVerifier.ResultType.CONTRADICTED);
        assertThat(result.correction()).isEqualTo("Correction at boundary");
    }

    @Test
    void verify_boundaryConfidence_justBelowThreshold() {
        FakeLlmClient fake = new FakeLlmClient();
        fake.enqueue(verdictResponse("CONTRADICTED", 0.79, "Low confidence correction", "Boundary test"));
        PostGenerationVerifier verifier = new PostGenerationVerifier(fake, "cheap-model", 0.8);

        var result = verifier.verify("q", "a", List.of(chunk("c1", "text")), CancellationToken.create());

        assertThat(result.type()).isEqualTo(PostGenerationVerifier.ResultType.CONTRADICTED);
        assertThat(result.correction()).isNull();
        assertThat(result.isActionable()).isFalse();
    }

    @Test
    void verify_emptySupplementalChunks_returnsVerifiedImmediately() {
        FakeLlmClient fake = new FakeLlmClient();
        PostGenerationVerifier verifier = new PostGenerationVerifier(fake, "cheap-model", 0.8);

        var result = verifier.verify("q", "a", List.of(), CancellationToken.create());

        assertThat(result.type()).isEqualTo(PostGenerationVerifier.ResultType.VERIFIED);
        assertThat(result.confidence()).isEqualTo(1.0);
        assertThat(result.reasoning()).contains("No supplemental chunks");
    }

    @Test
    void verify_nullRejection() {
        FakeLlmClient fake = new FakeLlmClient();
        PostGenerationVerifier verifier = new PostGenerationVerifier(fake, "cheap-model", 0.8);
        CancellationToken token = CancellationToken.create();
        List<Chunk> chunks = List.of(chunk("c1", "text"));

        assertThatNullPointerException().isThrownBy(() -> verifier.verify(null, "a", chunks, token));
        assertThatNullPointerException().isThrownBy(() -> verifier.verify("q", null, chunks, token));
        assertThatNullPointerException().isThrownBy(() -> verifier.verify("q", "a", null, token));
        assertThatNullPointerException().isThrownBy(() -> verifier.verify("q", "a", chunks, null));
    }

    // ---- helpers ----

    private Chunk chunk(String id, String text) {
        return new Chunk(id, "doc-" + id, text, 0, text.length() / 4, null, Map.of());
    }

    private ChatResponse verdictResponse(String verdict, double confidence, String correction, String reasoning) {
        String content = """
            VERDICT: %s
            CONFIDENCE: %s
            CORRECTION: %s
            REASONING: %s
            """.formatted(verdict, confidence, correction, reasoning);
        return new ChatResponse(
            "r1", "cheap-model", "fake",
            Message.assistant(content),
            new TokenCount(10, 5, "fake"),
            Duration.ZERO, "stop", null, null, Map.of()
        );
    }

    static final class FakeLlmClient implements LlmClient {
        private final Queue<ChatResponse> responses = new ConcurrentLinkedQueue<>();

        void enqueue(ChatResponse response) {
            responses.add(response);
        }

        @Override
        public Flow.Publisher<StreamEvent> stream(ChatRequest request, CancellationToken cancellationToken) {
            throw new UnsupportedOperationException();
        }

        @Override
        public ChatResponse complete(ChatRequest request, CancellationToken cancellationToken) {
            ChatResponse r = responses.poll();
            if (r == null) {
                throw new IllegalStateException("No fake response enqueued");
            }
            return r;
        }

        @Override
        public HealthStatus health() {
            return HealthStatus.HEALTHY;
        }

        @Override
        public String providerName() {
            return "fake";
        }
    }
}
