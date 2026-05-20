package com.chorus.engine.tools;

import com.chorus.engine.core.reactive.CancellationToken;
import com.chorus.engine.core.result.Result;
import org.jspecify.annotations.NonNull;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.stream.Collectors;

/**
 * Command execution with safety controls.
 *
 * <p>Operations:
 * <ul>
 *   <li>{@code execute_command} – runs a single shell command</li>
 *   <li>{@code execute_script} – runs a script through an interpreter</li>
 * </ul>
 *
 * <p>Safety layers:
 * <ul>
 *   <li>Base command must appear in the {@code allowlist}</li>
 *   <li>Full command is checked against {@link SafetyValidator}</li>
 *   <li>Hard timeout enforced via {@link CompletableFuture}</li>
 * </ul>
 */
public final class ShellTool implements Tool {

    private final List<String> allowlist;
    private final Duration timeout;

    public ShellTool(@NonNull List<String> allowlist, @NonNull Duration timeout) {
        this.allowlist = List.copyOf(allowlist);
        this.timeout = timeout;
    }

    @Override
    public @NonNull String name() {
        return "shell";
    }

    @Override
    public @NonNull String description() {
        return "Execute shell commands with safety controls. Supports execute_command and execute_script.";
    }

    @Override
    public @NonNull Map<String, Object> parametersSchema() {
        return Map.of(
            "type", "object",
            "properties", Map.of(
                "operation", Map.of("type", "string", "enum", List.of("execute_command", "execute_script")),
                "command", Map.of("type", "string", "description", "Command to execute (for execute_command)"),
                "script", Map.of("type", "string", "description", "Script content (for execute_script)"),
                "interpreter", Map.of("type", "string", "description", "Interpreter for script (default: sh)")
            ),
            "required", List.of("operation")
        );
    }

    @Override
    public @NonNull Result<ToolOutput, ToolError> execute(@NonNull Map<String, Object> args, @NonNull CancellationToken token) {
        String operation = getString(args, "operation");
        if (operation == null) {
            return Result.err(new ToolError.ValidationError("operation", "Missing operation"));
        }

        return switch (operation) {
            case "execute_command" -> {
                String command = getString(args, "command");
                if (command == null || command.isBlank()) {
                    yield Result.err(new ToolError.ValidationError("command", "Command is required"));
                }
                yield runCommand(command);
            }
            case "execute_script" -> {
                String script = getString(args, "script");
                String interpreter = Objects.requireNonNullElse(getString(args, "interpreter"), "sh");
                if (script == null || script.isBlank()) {
                    yield Result.err(new ToolError.ValidationError("script", "Script is required"));
                }
                if (!allowlist.contains(interpreter)) {
                    yield Result.err(new ToolError.SafetyBlocked("Interpreter not in allowlist: " + interpreter));
                }
                yield runScript(interpreter, script);
            }
            default -> Result.err(new ToolError.ValidationError("operation", "Unknown operation: " + operation));
        };
    }

    private Result<ToolOutput, ToolError> runCommand(String command) {
        String baseCommand = extractBaseCommand(command);
        if (!allowlist.contains(baseCommand)) {
            return Result.err(new ToolError.SafetyBlocked("Command not in allowlist: " + baseCommand));
        }

        Optional<String> safety = SafetyValidator.validateShellCommand(command);
        if (safety.isPresent()) {
            return Result.err(new ToolError.SafetyBlocked(safety.get()));
        }

        try {
            ProcessBuilder pb = new ProcessBuilder("/bin/sh", "-c", command);
            pb.redirectErrorStream(true);
            Process process = pb.start();

            CompletableFuture<String> outputFuture = CompletableFuture.supplyAsync(() -> {
                try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                    return reader.lines().collect(Collectors.joining("\n"));
                } catch (IOException e) {
                    throw new CompletionException(e);
                }
            });

            String output;
            try {
                output = outputFuture.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
            } catch (TimeoutException e) {
                process.destroyForcibly();
                return Result.err(new ToolError.TimeoutError(timeout));
            } catch (ExecutionException e) {
                process.destroyForcibly();
                return Result.err(new ToolError.ExecutionError(command, e.getCause().getMessage(), 1));
            }

            boolean finished = process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS);
            if (!finished) {
                process.destroyForcibly();
                return Result.err(new ToolError.TimeoutError(timeout));
            }

            int exitCode = process.exitValue();
            if (exitCode != 0) {
                return Result.err(new ToolError.ExecutionError(command, output, exitCode));
            }

            Map<String, Object> structured = new LinkedHashMap<>();
            structured.put("exitCode", exitCode);
            structured.put("command", command);
            return Result.ok(new ToolOutput(output, structured));

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return Result.err(new ToolError.ExecutionError(command, "Interrupted", 1));
        } catch (IOException e) {
            return Result.err(new ToolError.ExecutionError(command, e.getMessage(), 1));
        }
    }

    private Result<ToolOutput, ToolError> runScript(String interpreter, String script) {
        String label = interpreter + " -c <script>";
        try {
            ProcessBuilder pb = new ProcessBuilder(interpreter, "-c", script);
            pb.redirectErrorStream(true);
            Process process = pb.start();

            CompletableFuture<String> outputFuture = CompletableFuture.supplyAsync(() -> {
                try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                    return reader.lines().collect(Collectors.joining("\n"));
                } catch (IOException e) {
                    throw new CompletionException(e);
                }
            });

            String output;
            try {
                output = outputFuture.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
            } catch (TimeoutException e) {
                process.destroyForcibly();
                return Result.err(new ToolError.TimeoutError(timeout));
            } catch (ExecutionException e) {
                process.destroyForcibly();
                return Result.err(new ToolError.ExecutionError(label, e.getCause().getMessage(), 1));
            }

            boolean finished = process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS);
            if (!finished) {
                process.destroyForcibly();
                return Result.err(new ToolError.TimeoutError(timeout));
            }

            int exitCode = process.exitValue();
            if (exitCode != 0) {
                return Result.err(new ToolError.ExecutionError(label, output, exitCode));
            }

            Map<String, Object> structured = new LinkedHashMap<>();
            structured.put("exitCode", exitCode);
            structured.put("command", label);
            return Result.ok(new ToolOutput(output, structured));

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return Result.err(new ToolError.ExecutionError(label, "Interrupted", 1));
        } catch (IOException e) {
            return Result.err(new ToolError.ExecutionError(label, e.getMessage(), 1));
        }
    }

    private static String extractBaseCommand(String command) {
        String trimmed = command.trim();
        int spaceIdx = trimmed.indexOf(' ');
        return spaceIdx > 0 ? trimmed.substring(0, spaceIdx) : trimmed;
    }

    private static String getString(Map<String, Object> args, String key) {
        Object v = args.get(key);
        return v instanceof String str ? str : null;
    }
}
