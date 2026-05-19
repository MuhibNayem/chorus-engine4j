package com.chorus.engine.llm.embed;

import com.chorus.engine.core.result.Result;
import org.jspecify.annotations.NonNull;

import java.util.List;
import java.util.Map;

/**
 * Completely decoupled embedding client interface.
 *
 * <p>The framework never instantiates an implementation. The user injects
 * whichever client they want: OpenAI, Gemini, Ollama, vLLM, ONNX, or custom.
 */
public interface EmbeddingClient {

    @NonNull Result<float[], EmbeddingError> embed(@NonNull String text, @NonNull EmbedOptions options);

    @NonNull Result<List<float[]>, EmbeddingError> embedBatch(@NonNull List<String> texts, @NonNull EmbedOptions options);

    @NonNull String providerName();
    @NonNull String modelName();
    int nativeDimensions();
    boolean isLocal();
    @NonNull HealthStatus health();

    enum HealthStatus { HEALTHY, DEGRADED, UNAVAILABLE }

    record EmbedOptions(
        @NonNull String model,
        @NonNull InputType inputType,
        int dimensions,
        boolean normalize,
        @NonNull Quantization quantization,
        @NonNull Map<String, Object> providerExtras
    ) {
        public EmbedOptions {
            providerExtras = Map.copyOf(providerExtras);
            if (dimensions < 0) throw new IllegalArgumentException("dimensions must be >= 0");
        }
        public enum InputType { QUERY, DOCUMENT, UNSPECIFIED }
        public enum Quantization { FP32, FP16, INT8, BINARY }

        public static @NonNull EmbedOptions defaults(@NonNull String model) {
            return new EmbedOptions(model, InputType.UNSPECIFIED, 0, true, Quantization.FP32, Map.of());
        }
        public @NonNull EmbedOptions withDimensions(int dims) {
            return new EmbedOptions(model, inputType, dims, normalize, quantization, providerExtras);
        }
    }

    record EmbeddingError(@NonNull String code, @NonNull String message, @NonNull String provider, int httpStatus) {
        public static @NonNull EmbeddingError of(@NonNull String code, @NonNull String message, @NonNull String provider) {
            return new EmbeddingError(code, message, provider, 0);
        }
    }
}
