package com.chorus.engine.llm.embed;

import com.chorus.engine.core.result.Result;
import com.chorus.engine.llm.retry.RetryPolicy;
import com.chorus.engine.llm.testutil.FakeHttpClient;
import com.chorus.engine.llm.testutil.FakeHttpResponse;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.*;

class OpenAiEmbeddingClientTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private FakeHttpClient fakeClient;
    private OpenAiEmbeddingClient client;

    @BeforeEach
    void setUp() {
        fakeClient = new FakeHttpClient();
        RetryPolicy retryPolicy = new RetryPolicy(
            3, Duration.ZERO, Duration.ZERO, 0.0,
            Set.of(429, 500, 502, 503, 504),
            Set.of(),
            Duration.ofSeconds(30)
        );
        client = new OpenAiEmbeddingClient(
            "openai-embed",
            "http://localhost:8080",
            "fake-key",
            "text-embedding-3-small",
            3,
            fakeClient,
            MAPPER,
            retryPolicy
        );
    }

    private FakeHttpResponse<String> embeddingResponse(float[]... vectors) {
        StringBuilder sb = new StringBuilder();
        sb.append("{\"data\":[");
        for (int i = 0; i < vectors.length; i++) {
            if (i > 0) sb.append(",");
            sb.append("{\"embedding\":[");
            for (int j = 0; j < vectors[i].length; j++) {
                if (j > 0) sb.append(",");
                sb.append(vectors[i][j]);
            }
            sb.append("]}");
        }
        sb.append("]}");
        return new FakeHttpResponse<>(200, sb.toString());
    }

    @Test
    void embed_with_valid_response() {
        fakeClient.enqueue(embeddingResponse(new float[]{0.1f, 0.2f, 0.3f}));

        Result<float[], EmbeddingClient.EmbeddingError> result =
            client.embed("hello", new EmbeddingClient.EmbedOptions("text-embedding-3-small", EmbeddingClient.EmbedOptions.InputType.UNSPECIFIED, 0, false, EmbeddingClient.EmbedOptions.Quantization.FP32, Map.of()));

        assertThat(result.isOk()).isTrue();
        float[] vec = result.unwrap();
        assertThat(vec).containsExactly(0.1f, 0.2f, 0.3f);
    }

    @Test
    void embedBatch_with_valid_response() {
        fakeClient.enqueue(embeddingResponse(new float[]{0.1f, 0.2f, 0.3f}, new float[]{0.4f, 0.5f, 0.6f}));

        Result<List<float[]>, EmbeddingClient.EmbeddingError> result = client.embedBatch(
            List.of("hello", "world"),
            new EmbeddingClient.EmbedOptions("text-embedding-3-small", EmbeddingClient.EmbedOptions.InputType.UNSPECIFIED, 0, false, EmbeddingClient.EmbedOptions.Quantization.FP32, Map.of())
        );

        assertThat(result.isOk()).isTrue();
        List<float[]> vectors = result.unwrap();
        assertThat(vectors).hasSize(2);
        assertThat(vectors.get(0)).containsExactly(0.1f, 0.2f, 0.3f);
        assertThat(vectors.get(1)).containsExactly(0.4f, 0.5f, 0.6f);
    }

    @Test
    void normalization_produces_unit_vector() {
        fakeClient.enqueue(embeddingResponse(new float[]{3.0f, 4.0f, 0.0f}));

        Result<float[], EmbeddingClient.EmbeddingError> result = client.embed("text",
            new EmbeddingClient.EmbedOptions("text-embedding-3-small", EmbeddingClient.EmbedOptions.InputType.UNSPECIFIED, 0, true,
                EmbeddingClient.EmbedOptions.Quantization.FP32, Map.of()));

        assertThat(result.isOk()).isTrue();
        float[] vec = result.unwrap();
        double norm = Math.sqrt(vec[0] * vec[0] + vec[1] * vec[1] + vec[2] * vec[2]);
        assertThat(norm).isCloseTo(1.0, offset(0.0001));
    }

    @Test
    void handling_404_not_found() {
        fakeClient.enqueue(new FakeHttpResponse<>(404, "not found"));

        Result<float[], EmbeddingClient.EmbeddingError> result =
            client.embed("hello", EmbeddingClient.EmbedOptions.defaults("text-embedding-3-small"));

        assertThat(result.isErr()).isTrue();
        assertThat(result.unwrapErr().code()).isEqualTo("EMBED_FAILED");
        assertThat(result.unwrapErr().message()).contains("404");
    }

    @Test
    void retry_loop_eventually_succeeds() {
        fakeClient.enqueue(new FakeHttpResponse<>(503, "overloaded"));
        fakeClient.enqueue(embeddingResponse(new float[]{0.1f, 0.2f, 0.3f}));

        Result<float[], EmbeddingClient.EmbeddingError> result =
            client.embed("hello", new EmbeddingClient.EmbedOptions("text-embedding-3-small", EmbeddingClient.EmbedOptions.InputType.UNSPECIFIED, 0, false, EmbeddingClient.EmbedOptions.Quantization.FP32, Map.of()));

        assertThat(result.isOk()).isTrue();
        assertThat(result.unwrap()).containsExactly(0.1f, 0.2f, 0.3f);
    }

    @Test
    void null_input_rejected() {
        assertThatThrownBy(() -> client.embed(null, EmbeddingClient.EmbedOptions.defaults("m")))
            .isInstanceOf(NullPointerException.class);
    }

    @Test
    void empty_batch_returns_empty_result() {
        fakeClient.enqueue(new FakeHttpResponse<>(200, "{\"data\":[]}"));

        Result<List<float[]>, EmbeddingClient.EmbeddingError> result =
            client.embedBatch(List.of(), EmbeddingClient.EmbedOptions.defaults("m"));

        assertThat(result.isOk()).isTrue();
        assertThat(result.unwrap()).isEmpty();
    }
}
