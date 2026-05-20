package com.chorus.engine.springboot.tool;

import com.chorus.engine.annotation.ToolParam;
import com.chorus.engine.core.reactive.CancellationToken;
import com.chorus.engine.core.result.Result;
import com.chorus.engine.tools.Tool;
import com.chorus.engine.tools.ToolError;
import com.chorus.engine.tools.ToolOutput;
import org.jspecify.annotations.NonNull;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.lang.reflect.ParameterizedType;
import java.util.*;

/**
 * Adapts a method on a Spring bean to the {@link Tool} interface.
 *
 * <p>Each {@code @Tool}-annotated method on an {@code @Agent} class gets its
 * own {@code AnnotatedMethodTool} instance. The tool's
 * {@link #execute(Map, CancellationToken)} method:
 * <ol>
 *   <li>Maps JSON args to method parameters using {@code @ToolParam} names</li>
 *   <li>Passes through {@code CancellationToken} and raw args map for
 *       specially-typed parameters</li>
 *   <li>Invokes the method reflectively</li>
 *   <li>Wraps the return value into a {@code Result<ToolOutput, ToolError>}</li>
 * </ol>
 *
 * <p>The {@link Method} object is resolved lazily (on first use) from the
 * {@code methodName} and {@code paramTypeNames} stored at construction time.
 * This makes the bean definition AOT-friendly because no {@code Method}
 * objects need to be captured in generated code.
 */
public final class AnnotatedMethodTool implements Tool {

    private final String name;
    private final String description;
    private final Map<String, Object> parametersSchema;
    private final Object target;
    private final String methodName;
    private final List<String> paramTypeNames;
    private final List<ParamBinding> paramBindings;

    private volatile Method method;

    public AnnotatedMethodTool(
        @NonNull String name,
        @NonNull String description,
        @NonNull Map<String, Object> parametersSchema,
        @NonNull Object target,
        @NonNull String methodName,
        @NonNull List<String> paramTypeNames,
        @NonNull List<ParamBinding> paramBindings
    ) {
        this.name = name;
        this.description = description;
        this.parametersSchema = parametersSchema;
        this.target = target;
        this.methodName = methodName;
        this.paramTypeNames = List.copyOf(paramTypeNames);
        this.paramBindings = List.copyOf(paramBindings);
    }

    @Override
    public @NonNull String name() {
        return name;
    }

    @Override
    public @NonNull String description() {
        return description;
    }

    @Override
    public @NonNull Map<String, Object> parametersSchema() {
        return parametersSchema;
    }

    @Override
    public @NonNull Result<ToolOutput, ToolError> execute(
        @NonNull Map<String, Object> args,
        @NonNull CancellationToken token
    ) {
        try {
            Method m = resolveMethod();
            Object[] params = resolveParameters(args, token);
            Object result = m.invoke(target, params);
            return wrapResult(result);
        } catch (NoSuchMethodException e) {
            return Result.err(new ToolError.ExecutionError(
                name, "Method not found: " + methodName + " on " + target.getClass().getName(), -1));
        } catch (InvocationTargetException e) {
            Throwable cause = e.getCause() != null ? e.getCause() : e;
            return Result.err(new ToolError.ExecutionError(
                name, cause.getMessage() != null ? cause.getMessage() : cause.getClass().getSimpleName(), -1));
        } catch (IllegalAccessException e) {
            return Result.err(new ToolError.ExecutionError(
                name, "Illegal access: " + e.getMessage(), -1));
        } catch (Exception e) {
            return Result.err(new ToolError.ExecutionError(
                name, e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName(), -1));
        }
    }

    // ------------------------------------------------------------------
    // Internal
    // ------------------------------------------------------------------

    private Method resolveMethod() throws NoSuchMethodException {
        Method m = method;
        if (m == null) {
            synchronized (this) {
                m = method;
                if (m == null) {
                    Class<?>[] paramTypes = paramTypeNames.stream()
                        .map(this::resolveClass)
                        .toArray(Class<?>[]::new);
                    m = target.getClass().getMethod(methodName, paramTypes);
                    method = m;
                }
            }
        }
        return m;
    }

    private Class<?> resolveClass(String name) {
        return switch (name) {
            case "boolean" -> boolean.class;
            case "byte" -> byte.class;
            case "short" -> short.class;
            case "int" -> int.class;
            case "long" -> long.class;
            case "float" -> float.class;
            case "double" -> double.class;
            case "char" -> char.class;
            default -> {
                try {
                    yield Class.forName(name);
                } catch (ClassNotFoundException e) {
                    yield Object.class;
                }
            }
        };
    }

    private Object[] resolveParameters(Map<String, Object> args, CancellationToken token) {
        Object[] params = new Object[paramBindings.size()];
        for (int i = 0; i < paramBindings.size(); i++) {
            ParamBinding binding = paramBindings.get(i);
            params[i] = switch (binding.specialKind()) {
                case ARG -> resolveArg(binding, args);
                case RAW_ARGS -> args;
                case CANCELLATION_TOKEN -> token;
            };
        }
        return params;
    }

    @SuppressWarnings("unchecked")
    private Object resolveArg(ParamBinding binding, Map<String, Object> args) {
        Object value = args.get(binding.name());
        if (value == null && !args.containsKey(binding.name())) {
            return defaultValue(binding.type());
        }
        return coerce(value, binding.type());
    }

    private Object defaultValue(java.lang.reflect.Type type) {
        Class<?> raw = rawClass(type);
        if (raw == int.class) return 0;
        if (raw == long.class) return 0L;
        if (raw == short.class) return (short) 0;
        if (raw == byte.class) return (byte) 0;
        if (raw == double.class) return 0.0;
        if (raw == float.class) return 0.0f;
        if (raw == boolean.class) return false;
        if (raw == char.class) return '\0';
        return null;
    }

    @SuppressWarnings("unchecked")
    private Object coerce(Object value, java.lang.reflect.Type type) {
        if (value == null) {
            return null;
        }
        Class<?> raw = rawClass(type);
        if (raw.isInstance(value)) {
            return value;
        }
        // Number coercion
        if (value instanceof Number n) {
            if (raw == int.class || raw == Integer.class) return n.intValue();
            if (raw == long.class || raw == Long.class) return n.longValue();
            if (raw == short.class || raw == Short.class) return n.shortValue();
            if (raw == byte.class || raw == Byte.class) return n.byteValue();
            if (raw == double.class || raw == Double.class) return n.doubleValue();
            if (raw == float.class || raw == Float.class) return n.floatValue();
        }
        // String coercion for primitives
        if (value instanceof String s) {
            if (raw == int.class || raw == Integer.class) return Integer.parseInt(s);
            if (raw == long.class || raw == Long.class) return Long.parseLong(s);
            if (raw == double.class || raw == Double.class) return Double.parseDouble(s);
            if (raw == float.class || raw == Float.class) return Float.parseFloat(s);
            if (raw == boolean.class || raw == Boolean.class) return Boolean.parseBoolean(s);
        }
        // Enum coercion
        if (raw.isEnum() && value instanceof String s) {
            for (Object ec : raw.getEnumConstants()) {
                if (((Enum<?>) ec).name().equals(s)) {
                    return ec;
                }
            }
        }
        // Collection coercion from single element
        if (Collection.class.isAssignableFrom(raw) && !(value instanceof Collection)) {
            Collection<Object> col = new ArrayList<>();
            col.add(value);
            return col;
        }
        return value;
    }

    @SuppressWarnings("unchecked")
    private Result<ToolOutput, ToolError> wrapResult(Object result) {
        if (result == null) {
            return Result.ok(new ToolOutput("", null));
        }
        if (result instanceof Result<?, ?> r) {
            return (Result<ToolOutput, ToolError>) r;
        }
        if (result instanceof ToolOutput to) {
            return Result.ok(to);
        }
        if (result instanceof String s) {
            return Result.ok(ToolOutput.of(s));
        }
        return Result.ok(ToolOutput.of(result.toString()));
    }

    private static Class<?> rawClass(java.lang.reflect.Type type) {
        if (type instanceof Class<?> c) {
            return c;
        }
        if (type instanceof ParameterizedType pt) {
            return rawClass(pt.getRawType());
        }
        return Object.class;
    }

    // ------------------------------------------------------------------
    // Static factories for building metadata from a Method
    // ------------------------------------------------------------------

    /**
     * Build {@link ParamBinding}s by inspecting a method's parameters.
     */
    public static @NonNull List<ParamBinding> buildParamBindings(@NonNull Method method) {
        Parameter[] params = method.getParameters();
        List<ParamBinding> bindings = new ArrayList<>(params.length);

        for (Parameter param : params) {
            Class<?> type = param.getType();
            ParamBinding.SpecialKind kind;
            String name;
            boolean required;

            if (CancellationToken.class.isAssignableFrom(type)) {
                kind = ParamBinding.SpecialKind.CANCELLATION_TOKEN;
                name = "";
                required = false;
            } else if (Map.class.isAssignableFrom(type)
                       && param.getParameterizedType() instanceof ParameterizedType pt
                       && pt.getActualTypeArguments().length == 2
                       && pt.getActualTypeArguments()[0] == String.class
                       && pt.getActualTypeArguments()[1] == Object.class) {
                kind = ParamBinding.SpecialKind.RAW_ARGS;
                name = "";
                required = false;
            } else {
                ToolParam ann = param.getAnnotation(ToolParam.class);
                if (ann != null) {
                    name = param.getName();
                    required = ann.required();
                } else {
                    name = param.getName();
                    required = true;
                }
                kind = ParamBinding.SpecialKind.ARG;
            }
            bindings.add(new ParamBinding(name, param.getParameterizedType(), required, kind));
        }
        return List.copyOf(bindings);
    }

    /**
     * Build the list of fully-qualified parameter type names for a method.
     */
    public static @NonNull List<String> buildParamTypeNames(@NonNull Method method) {
        Class<?>[] types = method.getParameterTypes();
        List<String> names = new ArrayList<>(types.length);
        for (Class<?> type : types) {
            names.add(type.getName());
        }
        return List.copyOf(names);
    }
}
