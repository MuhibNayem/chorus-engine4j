package com.chorus.engine.llm;

import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

import java.util.Map;
import java.util.Objects;

/**
 * Structured output format constraint.
 */
public record ResponseFormat(
    @NonNull Type type,
    @Nullable Map<String, Object> jsonSchema
) {
    public ResponseFormat {
        Objects.requireNonNull(type);
    }

    public enum Type { TEXT, JSON_OBJECT, JSON_SCHEMA }

    public static @NonNull ResponseFormat text() {
        return new ResponseFormat(Type.TEXT, null);
    }

    public static @NonNull ResponseFormat json() {
        return new ResponseFormat(Type.JSON_OBJECT, null);
    }

    public static @NonNull ResponseFormat jsonSchema(@NonNull Map<String, Object> schema) {
        return new ResponseFormat(Type.JSON_SCHEMA, Map.copyOf(schema));
    }
}
