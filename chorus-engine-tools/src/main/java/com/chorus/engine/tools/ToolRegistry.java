package com.chorus.engine.tools;

import com.chorus.engine.core.reactive.CancellationToken;
import com.chorus.engine.core.result.Result;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * Thread-safe registry for {@link Tool} instances.
 *
 * <p>Supports registration, discovery by keyword, and guarded execution
 * with lightweight JSON Schema required-field validation.
 */
public final class ToolRegistry {

    private final ConcurrentHashMap<String, Tool> tools = new ConcurrentHashMap<>();

    public void register(@NonNull Tool tool) {
        Tool existing = tools.putIfAbsent(tool.name(), tool);
        if (existing != null) {
            throw new IllegalArgumentException("Tool already registered: " + tool.name());
        }
    }

    public @Nullable Tool find(@NonNull String name) {
        return tools.get(name);
    }

    /**
     * Discover tools whose name or description contains the query (case-insensitive).
     */
    public @NonNull List<Tool> discover(@NonNull String query) {
        String lowerQuery = query.toLowerCase(Locale.ROOT);
        return tools.values().stream()
            .filter(t -> t.name().toLowerCase(Locale.ROOT).contains(lowerQuery)
                      || t.description().toLowerCase(Locale.ROOT).contains(lowerQuery))
            .collect(Collectors.toList());
    }

    /**
     * Look up a tool by name, validate required arguments, and execute.
     */
    public @NonNull Result<ToolOutput, ToolError> execute(
            @NonNull String name,
            @NonNull Map<String, Object> args,
            @NonNull CancellationToken token) {
        Tool tool = tools.get(name);
        if (tool == null) {
            return Result.err(new ToolError.NotFound(name));
        }
        Optional<ToolError> validationError = validateArgs(tool, args);
        if (validationError.isPresent()) {
            return Result.err(validationError.get());
        }
        return tool.execute(args, token);
    }

    public @NonNull Collection<Tool> allTools() {
        return List.copyOf(tools.values());
    }

    @SuppressWarnings("unchecked")
    private Optional<ToolError> validateArgs(@NonNull Tool tool, @NonNull Map<String, Object> args) {
        Map<String, Object> schema = tool.parametersSchema();
        Object requiredObj = schema.get("required");
        if (requiredObj instanceof List<?> requiredList) {
            for (Object req : requiredList) {
                if (req instanceof String key && !args.containsKey(key)) {
                    return Optional.of(new ToolError.ValidationError(key, "Missing required parameter"));
                }
            }
        }
        return Optional.empty();
    }
}
