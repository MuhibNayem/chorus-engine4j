package com.chorus.engine.tools;

import com.chorus.engine.core.reactive.CancellationToken;
import com.chorus.engine.core.result.Result;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.*;

class ShellToolTest {

    @Test
    void executeCommand_success() {
        ShellTool tool = new ShellTool(List.of("echo"), Duration.ofSeconds(5));
        Result<ToolOutput, ToolError> result = tool.execute(
            Map.of("operation", "execute_command", "command", "echo hello"),
            CancellationToken.create());

        assertThat(result.isOk()).isTrue();
        assertThat(result.unwrap().content()).contains("hello");
        assertThat(result.unwrap().structuredData()).containsEntry("exitCode", 0);
    }

    @Test
    void executeCommand_blocksDisallowedCommand() {
        ShellTool tool = new ShellTool(List.of("echo"), Duration.ofSeconds(5));
        Result<ToolOutput, ToolError> result = tool.execute(
            Map.of("operation", "execute_command", "command", "cat /etc/passwd"),
            CancellationToken.create());

        assertThat(result.isErr()).isTrue();
        assertThat(result.unwrapErr()).isInstanceOf(ToolError.SafetyBlocked.class);
    }

    @Test
    void executeCommand_blocksDangerousPattern() {
        ShellTool tool = new ShellTool(List.of("sh", "rm"), Duration.ofSeconds(5));
        Result<ToolOutput, ToolError> result = tool.execute(
            Map.of("operation", "execute_command", "command", "rm -rf /"),
            CancellationToken.create());

        assertThat(result.isErr()).isTrue();
        assertThat(result.unwrapErr()).isInstanceOf(ToolError.SafetyBlocked.class);
    }

    @Test
    void executeCommand_nonZeroExitCode() {
        ShellTool tool = new ShellTool(List.of("sh"), Duration.ofSeconds(5));
        Result<ToolOutput, ToolError> result = tool.execute(
            Map.of("operation", "execute_command", "command", "sh -c 'exit 42'"),
            CancellationToken.create());

        assertThat(result.isErr()).isTrue();
        assertThat(result.unwrapErr()).isInstanceOf(ToolError.ExecutionError.class);
        ToolError.ExecutionError err = (ToolError.ExecutionError) result.unwrapErr();
        assertThat(err.exitCode()).isEqualTo(42);
    }

    @Test
    void executeScript_success() {
        ShellTool tool = new ShellTool(List.of("sh"), Duration.ofSeconds(5));
        Result<ToolOutput, ToolError> result = tool.execute(
            Map.of("operation", "execute_script", "script", "echo script works", "interpreter", "sh"),
            CancellationToken.create());

        assertThat(result.isOk()).isTrue();
        assertThat(result.unwrap().content()).contains("script works");
    }

    @Test
    void executeScript_blocksDisallowedInterpreter() {
        ShellTool tool = new ShellTool(List.of("sh"), Duration.ofSeconds(5));
        Result<ToolOutput, ToolError> result = tool.execute(
            Map.of("operation", "execute_script", "script", "print('hi')", "interpreter", "python"),
            CancellationToken.create());

        assertThat(result.isErr()).isTrue();
        assertThat(result.unwrapErr()).isInstanceOf(ToolError.SafetyBlocked.class);
    }
}
