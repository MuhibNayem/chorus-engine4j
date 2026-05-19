package com.chorus.engine.llm;

import com.chorus.engine.core.context.Message;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Immutable LLM chat completion request.
 */
public record ChatRequest(
    @NonNull String model,
    @NonNull List<Message> messages,
    @NonNull List<ToolDefinition> tools,
    double temperature,
    int maxTokens,
    @Nullable ResponseFormat responseFormat,
    @Nullable String stopSequence,
    @Nullable Map<String, Object> providerExtras
) {
    public ChatRequest {
        Objects.requireNonNull(model);
        messages = List.copyOf(messages);
        tools = List.copyOf(tools);
        providerExtras = providerExtras != null ? Map.copyOf(providerExtras) : null;
        if (temperature < 0 || temperature > 2) {
            throw new IllegalArgumentException("temperature must be in [0, 2]");
        }
        if (maxTokens < 1) {
            throw new IllegalArgumentException("maxTokens must be >= 1");
        }
    }

    public static @NonNull Builder builder() {
        return new Builder();
    }

    public @NonNull ChatRequest withMessages(@NonNull List<Message> newMessages) {
        return new ChatRequest(model, newMessages, tools, temperature, maxTokens, responseFormat, stopSequence, providerExtras);
    }

    public @NonNull ChatRequest withTools(@NonNull List<ToolDefinition> newTools) {
        return new ChatRequest(model, messages, newTools, temperature, maxTokens, responseFormat, stopSequence, providerExtras);
    }

    public static final class Builder {
        private String model = "gpt-4o";
        private List<Message> messages = List.of();
        private List<ToolDefinition> tools = List.of();
        private double temperature = 0.7;
        private int maxTokens = 4096;
        private ResponseFormat responseFormat;
        private String stopSequence;
        private Map<String, Object> providerExtras;

        public Builder model(@NonNull String model) { this.model = model; return this; }
        public Builder messages(@NonNull List<Message> messages) { this.messages = messages; return this; }
        public Builder tools(@NonNull List<ToolDefinition> tools) { this.tools = tools; return this; }
        public Builder temperature(double temperature) { this.temperature = temperature; return this; }
        public Builder maxTokens(int maxTokens) { this.maxTokens = maxTokens; return this; }
        public Builder responseFormat(@NonNull ResponseFormat format) { this.responseFormat = format; return this; }
        public Builder stopSequence(@NonNull String stop) { this.stopSequence = stop; return this; }
        public Builder providerExtras(@NonNull Map<String, Object> extras) { this.providerExtras = extras; return this; }

        public @NonNull ChatRequest build() {
            return new ChatRequest(model, messages, tools, temperature, maxTokens, responseFormat, stopSequence, providerExtras);
        }
    }
}
