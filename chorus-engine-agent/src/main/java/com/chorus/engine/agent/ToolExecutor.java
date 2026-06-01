package com.chorus.engine.agent;

import com.chorus.engine.core.reactive.CancellationToken;
import com.chorus.engine.core.result.Result;
import com.chorus.engine.tools.ToolError;
import com.chorus.engine.tools.ToolRegistry;
import org.jspecify.annotations.NonNull;

import java.util.Map;

/**
 * Abstraction for executing a named tool with validated arguments.
 *
 * <p>Decouples {@link com.chorus.engine.agent.loop.AgentLoop} from the concrete
 * {@link ToolRegistry} so that custom execution strategies (sandboxing, quotas,
 * audit logging) can be plugged in without touching the loop itself.
 */
@FunctionalInterface
public interface ToolExecutor {

    /**
     * Execute the named tool and return its text output or an error message.
     *
     * @param toolName  name that matches a registered {@link com.chorus.engine.tools.Tool}
     * @param arguments validated argument map produced by the LLM
     * @param token     cancellation signal; implementations must honour it
     * @return {@code Ok(output)} on success, {@code Err(reason)} on failure
     */
    @NonNull Result<String, String> execute(
        @NonNull String toolName,
        @NonNull Map<String, Object> arguments,
        @NonNull CancellationToken token
    );

    /**
     * Adapt a {@link ToolRegistry} to this interface. Argument validation is
     * performed by the registry before delegation to the underlying {@link
     * com.chorus.engine.tools.Tool}.
     */
    static @NonNull ToolExecutor of(@NonNull ToolRegistry registry) {
        return (name, args, token) -> {
            Result<com.chorus.engine.tools.ToolOutput, ToolError> result = registry.execute(name, args, token);
            if (result.isOk()) {
                return Result.ok(result.unwrap().content());
            }
            return Result.err(describeError(result.unwrapErr()));
        };
    }

    private static String describeError(ToolError error) {
        return switch (error) {
            case ToolError.NotFound e       -> "Tool not found: " + e.toolName();
            case ToolError.ValidationError e -> "Invalid argument '" + e.field() + "': " + e.message();
            case ToolError.ExecutionError e  -> "Execution failed (exit " + e.exitCode() + "): " + e.stderr();
            case ToolError.TimeoutError e   -> "Tool timed out after " + e.timeout();
            case ToolError.SafetyBlocked e  -> "Safety policy blocked: " + e.reason();
        };
    }
}
