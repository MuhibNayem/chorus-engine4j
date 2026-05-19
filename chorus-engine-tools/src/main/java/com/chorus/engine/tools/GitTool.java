package com.chorus.engine.tools;

import com.chorus.engine.core.reactive.CancellationToken;
import com.chorus.engine.core.result.Result;
import org.jspecify.annotations.NonNull;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Read-only git operations.
 *
 * <p>All operations are safe by default — no commit, push, reset, or write.
 * Supports status, diff, log, branch, show, and blame.
 */
public final class GitTool implements Tool {

    private final Path repoPath;

    public GitTool(@NonNull Path repoPath) {
        this.repoPath = repoPath;
    }

    @Override
    public @NonNull String name() {
        return "git";
    }

    @Override
    public @NonNull String description() {
        return "Read-only git operations. Supports git_status, git_diff, git_log, git_branch, git_show, git_blame.";
    }

    @Override
    public @NonNull Map<String, Object> parametersSchema() {
        return Map.of(
            "type", "object",
            "properties", Map.of(
                "operation", Map.of("type", "string", "enum", List.of("git_status", "git_diff", "git_log", "git_branch", "git_show", "git_blame")),
                "path", Map.of("type", "string", "description", "File path for git_diff, git_show, git_blame"),
                "commit", Map.of("type", "string", "description", "Commit hash for git_show, git_log"),
                "maxCount", Map.of("type", "integer", "description", "Max commits for git_log")
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

        if (!Files.isDirectory(repoPath.resolve(".git")) && !Files.exists(repoPath.resolve(".git"))) {
            return Result.err(new ToolError.ValidationError("repo", "Not a git repository: " + repoPath));
        }

        token.throwIfCancelled();

        return switch (operation) {
            case "git_status" -> runGit("status", "--short");
            case "git_diff" -> {
                String path = getString(args, "path");
                if (path != null) {
                    yield runGit("diff", "--", path);
                }
                yield runGit("diff");
            }
            case "git_log" -> {
                String maxCountStr = args.get("maxCount") instanceof Number n ? String.valueOf(n.intValue()) : "10";
                String commit = getString(args, "commit");
                List<String> cmd = new ArrayList<>(List.of("log", "--pretty=format:%H|%an|%ae|%ad|%s", "--date=short", "-n", maxCountStr));
                if (commit != null) cmd.add(commit);
                yield runGitParsed(cmd, this::parseLog);
            }
            case "git_branch" -> runGitParsed(List.of("branch", "-a"), this::parseBranches);
            case "git_show" -> {
                String commit = getString(args, "commit");
                if (commit == null) {
                    yield Result.err(new ToolError.ValidationError("commit", "Commit hash required for git_show"));
                }
                yield runGit("show", "--stat", commit);
            }
            case "git_blame" -> {
                String path = getString(args, "path");
                if (path == null) {
                    yield Result.err(new ToolError.ValidationError("path", "Path required for git_blame"));
                }
                yield runGit("blame", "--line-porcelain", path);
            }
            default -> Result.err(new ToolError.ValidationError("operation", "Unknown operation: " + operation));
        };
    }

    private Result<ToolOutput, ToolError> runGit(String... args) {
        return runGitParsed(Arrays.asList(args), output -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("raw", output);
            return m;
        });
    }

    private Result<ToolOutput, ToolError> runGitParsed(List<String> args, Function<String, Map<String, Object>> parser) {
        List<String> command = new ArrayList<>();
        command.add("git");
        command.add("-C");
        command.add(repoPath.toString());
        command.addAll(args);

        try {
            ProcessBuilder pb = new ProcessBuilder(command);
            pb.redirectErrorStream(true);
            Process process = pb.start();

            String output;
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                output = reader.lines().collect(Collectors.joining("\n"));
            }

            int exitCode = process.waitFor();
            if (exitCode != 0) {
                return Result.err(new ToolError.ExecutionError(String.join(" ", command), output, exitCode));
            }

            Map<String, Object> structured = parser.apply(output);
            return Result.ok(new ToolOutput(output, structured));

        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            return Result.err(new ToolError.ExecutionError(String.join(" ", command), e.getMessage(), 1));
        }
    }

    private Map<String, Object> parseLog(String output) {
        List<Map<String, String>> commits = new ArrayList<>();
        for (String line : output.split("\n")) {
            String[] parts = line.split("\\|", 5);
            if (parts.length >= 5) {
                Map<String, String> commit = new LinkedHashMap<>();
                commit.put("hash", parts[0]);
                commit.put("author", parts[1]);
                commit.put("email", parts[2]);
                commit.put("date", parts[3]);
                commit.put("message", parts[4]);
                commits.add(commit);
            }
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("commits", commits);
        result.put("count", commits.size());
        return result;
    }

    private Map<String, Object> parseBranches(String output) {
        List<Map<String, Object>> branches = new ArrayList<>();
        for (String line : output.split("\n")) {
            String trimmed = line.trim();
            if (trimmed.isEmpty()) continue;
            boolean current = trimmed.startsWith("*");
            String name = current ? trimmed.substring(1).trim() : trimmed;
            Map<String, Object> branch = new LinkedHashMap<>();
            branch.put("name", name);
            branch.put("current", current);
            branches.add(branch);
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("branches", branches);
        return result;
    }

    private static String getString(Map<String, Object> args, String key) {
        Object v = args.get(key);
        return v instanceof String s ? s : null;
    }
}
