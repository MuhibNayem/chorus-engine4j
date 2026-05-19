package com.chorus.engine.llm;

import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

import java.util.Map;
import java.util.Objects;

/**
 * Definition of a tool available to the LLM.
 * Carries JSON Schema for structured function calling.
 */
public record ToolDefinition(
    @NonNull String name,
    @NonNull String description,
    @NonNull Map<String, Object> parametersSchema, // JSON Schema object
    @Nullable Map<String, Object> returnsSchema,
    boolean required
) {
    public ToolDefinition {
        Objects.requireNonNull(name);
        Objects.requireNonNull(description);
        parametersSchema = Map.copyOf(parametersSchema);
        returnsSchema = returnsSchema != null ? Map.copyOf(returnsSchema) : null;
        if (!name.matches("^[a-zA-Z0-9_-]+$")) {
            throw new IllegalArgumentException("Tool name must match [a-zA-Z0-9_-]+: " + name);
        }
    }

    public static @NonNull ToolDefinition of(@NonNull String name, @NonNull String description) {
        return new ToolDefinition(name, description, Map.of("type", "object", "properties", Map.of()), null, true);
    }

    public static @NonNull Builder builder(@NonNull String name, @NonNull String description) {
        return new Builder(name, description);
    }

    public static final class Builder {
        private final String name;
        private final String description;
        private Map<String, Object> parametersSchema = Map.of("type", "object", "properties", Map.of());
        private Map<String, Object> returnsSchema;
        private boolean required = true;

        Builder(String name, String description) {
            this.name = name;
            this.description = description;
        }

        public Builder parametersSchema(@NonNull Map<String, Object> schema) {
            this.parametersSchema = schema;
            return this;
        }

        public Builder returnsSchema(@NonNull Map<String, Object> schema) {
            this.returnsSchema = schema;
            return this;
        }

        public Builder required(boolean required) {
            this.required = required;
            return this;
        }

        public @NonNull ToolDefinition build() {
            return new ToolDefinition(name, description, parametersSchema, returnsSchema, required);
        }
    }
}
