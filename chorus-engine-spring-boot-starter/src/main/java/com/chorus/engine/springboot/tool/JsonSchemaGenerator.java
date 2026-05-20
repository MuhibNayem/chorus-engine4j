package com.chorus.engine.springboot.tool;

import org.jspecify.annotations.NonNull;

import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.util.*;

/**
 * Generates JSON Schema {@code Map<String, Object>} descriptors for Java types.
 * Used at AOT time (or startup) to build the {@code parametersSchema} for
 * {@code AnnotatedMethodTool}.
 *
 * <p>Supports primitives, wrappers, {@code String}, {@code List}, {@code Map},
 * and enums. Nested types are described as generic objects.
 */
public final class JsonSchemaGenerator {

    private JsonSchemaGenerator() {}

    /**
     * Build a JSON Schema object for a list of parameter bindings.
     *
     * @return schema in the form
     *         {@code { "type": "object", "properties": {...}, "required": [...] }}
     */
    public static @NonNull Map<String, Object> forParameters(@NonNull List<ParamBinding> bindings) {
        Map<String, Object> properties = new LinkedHashMap<>();
        List<String> required = new ArrayList<>();

        for (ParamBinding binding : bindings) {
            if (binding.isSpecial()) {
                continue;
            }
            properties.put(binding.name(), forType(binding.type()));
            if (binding.required()) {
                required.add(binding.name());
            }
        }

        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", properties);
        if (!required.isEmpty()) {
            schema.put("required", required);
        }
        return Collections.unmodifiableMap(schema);
    }

    /**
     * Build a JSON Schema fragment for a single Java type.
     */
    public static @NonNull Map<String, Object> forType(@NonNull Type type) {
        Class<?> raw = rawClass(type);

        if (raw == String.class || raw == CharSequence.class || raw == char.class || raw == Character.class) {
            return Map.of("type", "string");
        }
        if (raw == int.class || raw == Integer.class
            || raw == long.class || raw == Long.class
            || raw == short.class || raw == Short.class
            || raw == byte.class || raw == Byte.class) {
            return Map.of("type", "integer");
        }
        if (raw == double.class || raw == Double.class
            || raw == float.class || raw == Float.class) {
            return Map.of("type", "number");
        }
        if (raw == boolean.class || raw == Boolean.class) {
            return Map.of("type", "boolean");
        }
        if (raw.isEnum()) {
            @SuppressWarnings("unchecked")
            List<String> values = Arrays.stream(((Class<? extends Enum<?>>) raw).getEnumConstants())
                .map(Enum::name)
                .toList();
            Map<String, Object> schema = new LinkedHashMap<>();
            schema.put("type", "string");
            schema.put("enum", values);
            return Collections.unmodifiableMap(schema);
        }
        if (Collection.class.isAssignableFrom(raw) || raw.isArray()) {
            Type itemType = itemType(type);
            Map<String, Object> schema = new LinkedHashMap<>();
            schema.put("type", "array");
            schema.put("items", forType(itemType));
            return Collections.unmodifiableMap(schema);
        }
        if (Map.class.isAssignableFrom(raw)) {
            Map<String, Object> schema = new LinkedHashMap<>();
            schema.put("type", "object");
            return Collections.unmodifiableMap(schema);
        }

        // Default: describe as an object with unknown properties
        return Map.of("type", "object");
    }

    private static @NonNull Class<?> rawClass(@NonNull Type type) {
        if (type instanceof Class<?> c) {
            return c;
        }
        if (type instanceof ParameterizedType pt) {
            return rawClass(pt.getRawType());
        }
        return Object.class;
    }

    private static @NonNull Type itemType(@NonNull Type type) {
        if (type instanceof ParameterizedType pt && pt.getActualTypeArguments().length > 0) {
            return pt.getActualTypeArguments()[0];
        }
        if (type instanceof Class<?> c && c.isArray()) {
            return c.getComponentType();
        }
        return Object.class;
    }
}
