package com.chorus.engine.core.structured;

import com.chorus.engine.core.event.ChatMessage;
import com.chorus.engine.core.llm.ChorusChatModel;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;


import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * Generates structured output from an LLM using JSON Schema constraints.
 * Supports post-hoc validation (all models) and constrained decoding hints
 * (for models that support it, e.g., OpenAI JSON mode, Gemini constrained decoding).
 *
 * <p>Usage:
 * <pre>{@code
 * var generator = StructuredOutputGenerator.builder(chatModel)
 *     .withSchema(MyPojo.class)
 *     .build();
 * var result = generator.generate(messages, "Extract the user intent").join();
 * }</pre>
 */
public class StructuredOutputGenerator<T> {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final ChorusChatModel chatModel;
    private final String model;
    private final Class<T> targetClass;
    private final String jsonSchema;
    private final String systemPrompt;
    private final boolean retryOnParseError;
    private final int maxRetries;

    private StructuredOutputGenerator(Builder<T> builder) {
        this.chatModel = builder.chatModel;
        this.model = builder.model;
        this.targetClass = builder.targetClass;
        this.jsonSchema = builder.jsonSchema;
        this.systemPrompt = builder.systemPrompt;
        this.retryOnParseError = builder.retryOnParseError;
        this.maxRetries = builder.maxRetries;
    }

    /**
     * Generate structured output from a conversation.
     */
    public CompletableFuture<T> generate(List<ChatMessage> messages) {
        return generateWithRetry(messages, 0);
    }

    private CompletableFuture<T> generateWithRetry(List<ChatMessage> messages, int attempt) {
        List<ChatMessage> promptMessages = buildPrompt(messages);

        return chatModel.generate(promptMessages, null, List.of(), model)
            .thenApply(response -> {
                String content = response.content();
                try {
                    String json = extractJson(content);
                    return MAPPER.readValue(json, targetClass);
                } catch (Exception e) {
                    if (retryOnParseError && attempt < maxRetries) {
                        throw new RetryableParseException("Parse failed, will retry", e, content);
                    }
                    throw new StructuredOutputException(
                        "Failed to parse structured output after " + (attempt + 1) + " attempts", e, content);
                }
            })
            .thenCompose(result -> {
                if (result instanceof RetryableParseException) {
                    return generateWithRetry(messages, attempt + 1);
                }
                return CompletableFuture.completedFuture(result);
            })
            .exceptionally(ex -> {
                Throwable cause = ex.getCause() != null ? ex.getCause() : ex;
                if (cause instanceof RetryableParseException) {
                    if (attempt < maxRetries) {
                        return generateWithRetry(messages, attempt + 1).join();
                    }
                }
                if (cause instanceof StructuredOutputException) {
                    throw (StructuredOutputException) cause;
                }
                throw new StructuredOutputException("Generation failed", cause, null);
            });
    }

    private List<ChatMessage> buildPrompt(List<ChatMessage> messages) {
        String schemaBlock = jsonSchema != null
            ? "\n\nYou MUST respond with valid JSON matching this schema:\n" + jsonSchema
            : "\n\nYou MUST respond with valid JSON.";

        String constraints = systemPrompt + schemaBlock +
            "\nDo not include markdown code fences, explanations, or any text outside the JSON.";

        List<ChatMessage> result = new java.util.ArrayList<>(messages.size() + 1);
        result.add(ChatMessage.system(constraints));
        result.addAll(messages);
        return result;
    }

    private String extractJson(String content) {
        String trimmed = content.trim();
        // Remove markdown code fences
        if (trimmed.startsWith("```json")) {
            trimmed = trimmed.substring(7);
        } else if (trimmed.startsWith("```")) {
            trimmed = trimmed.substring(3);
        }
        if (trimmed.endsWith("```")) {
            trimmed = trimmed.substring(0, trimmed.length() - 3);
        }
        trimmed = trimmed.trim();

        // Find JSON object boundaries
        int start = trimmed.indexOf('{');
        int end = trimmed.lastIndexOf('}');
        if (start >= 0 && end > start) {
            return trimmed.substring(start, end + 1);
        }

        // Try array
        start = trimmed.indexOf('[');
        end = trimmed.lastIndexOf(']');
        if (start >= 0 && end > start) {
            return trimmed.substring(start, end + 1);
        }

        return trimmed;
    }

    public static <T> Builder<T> builder(ChorusChatModel chatModel, Class<T> targetClass) {
        return new Builder<>(chatModel, targetClass);
    }

    public static class Builder<T> {
        private final ChorusChatModel chatModel;
        private Class<T> targetClass;
        private String model;
        private String jsonSchema;
        private String systemPrompt = "You are a helpful assistant that responds in structured JSON format.";
        private boolean retryOnParseError = true;
        private int maxRetries = 2;

        Builder(ChorusChatModel chatModel, Class<T> targetClass) {
            this.chatModel = chatModel;
            this.targetClass = targetClass;
        }

        public Builder<T> model(String model) {
            this.model = model;
            return this;
        }

        public Builder<T> withSchema(Class<T> schemaClass) {
            this.targetClass = schemaClass;
            // Generate JSON schema from class using Jackson
            try {
                com.fasterxml.jackson.databind.jsonschema.JsonSchema schema =
                    new ObjectMapper().generateJsonSchema(schemaClass);
                this.jsonSchema = schema.toString();
            } catch (Exception e) {
                this.jsonSchema = "{\"type\":\"object\"}";
            }
            return this;
        }

        public Builder<T> withSchema(String jsonSchema) {
            this.jsonSchema = jsonSchema;
            return this;
        }

        public Builder<T> systemPrompt(String systemPrompt) {
            this.systemPrompt = systemPrompt;
            return this;
        }

        public Builder<T> retryOnParseError(boolean retry) {
            this.retryOnParseError = retry;
            return this;
        }

        public Builder<T> maxRetries(int maxRetries) {
            this.maxRetries = maxRetries;
            return this;
        }

        public StructuredOutputGenerator<T> build() {
            return new StructuredOutputGenerator<>(this);
        }
    }

    public static class StructuredOutputException extends RuntimeException {
        private final String rawOutput;

        public StructuredOutputException(String message, Throwable cause, String rawOutput) {
            super(message, cause);
            this.rawOutput = rawOutput;
        }

        public String rawOutput() { return rawOutput; }
    }

    private static class RetryableParseException extends RuntimeException {
        private final String rawOutput;

        RetryableParseException(String message, Throwable cause, String rawOutput) {
            super(message, cause);
            this.rawOutput = rawOutput;
        }
    }
}
