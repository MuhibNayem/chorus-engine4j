package com.chorus.engine.tools;

import com.chorus.engine.core.reactive.CancellationToken;
import com.chorus.engine.core.result.Result;
import org.jspecify.annotations.NonNull;

import java.io.IOException;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Safe filesystem operations with path sandboxing.
 *
 * <p>All paths are resolved against a {@code sandboxRoot}. Path traversal
 * ({@code ..}), home references ({@code ~}), and absolute paths outside the
 * sandbox are rejected before any I/O occurs.
 *
 * <p>Supported operations:
 * <ul>
 *   <li>{@code read_file}</li>
 *   <li>{@code write_file}</li>
 *   <li>{@code list_directory}</li>
 *   <li>{@code glob_search}</li>
 *   <li>{@code file_info}</li>
 * </ul>
 */
public final class FilesystemTool implements Tool {

    private final Path sandboxRoot;

    public FilesystemTool(@NonNull Path sandboxRoot) {
        this.sandboxRoot = sandboxRoot.toAbsolutePath().normalize();
    }

    @Override
    public @NonNull String name() {
        return "filesystem";
    }

    @Override
    public @NonNull String description() {
        return "Safe filesystem operations within a sandboxed directory. Supports read_file, write_file, list_directory, glob_search, file_info.";
    }

    @Override
    public @NonNull Map<String, Object> parametersSchema() {
        return Map.of(
            "type", "object",
            "properties", Map.of(
                "operation", Map.of("type", "string", "enum", List.of("read_file", "write_file", "list_directory", "glob_search", "file_info")),
                "path", Map.of("type", "string"),
                "content", Map.of("type", "string"),
                "pattern", Map.of("type", "string")
            ),
            "required", List.of("operation", "path")
        );
    }

    @Override
    public @NonNull Result<ToolOutput, ToolError> execute(@NonNull Map<String, Object> args, @NonNull CancellationToken token) {
        String operation = getString(args, "operation");
        String pathStr = getString(args, "path");
        if (operation == null || pathStr == null) {
            return Result.err(new ToolError.ValidationError("args", "Missing operation or path"));
        }

        Path targetPath;
        try {
            targetPath = sandboxRoot.resolve(pathStr).normalize();
        } catch (Exception e) {
            return Result.err(new ToolError.ValidationError("path", "Invalid path: " + e.getMessage()));
        }

        Optional<String> safetyError = SafetyValidator.validatePath(targetPath, sandboxRoot);
        if (safetyError.isPresent()) {
            return Result.err(new ToolError.SafetyBlocked(safetyError.get()));
        }

        token.throwIfCancelled();

        return switch (operation) {
            case "read_file" -> readFile(targetPath);
            case "write_file" -> writeFile(targetPath, getString(args, "content"));
            case "list_directory" -> listDirectory(targetPath);
            case "glob_search" -> globSearch(targetPath, getString(args, "pattern"));
            case "file_info" -> fileInfo(targetPath);
            default -> Result.err(new ToolError.ValidationError("operation", "Unknown operation: " + operation));
        };
    }

    private static String getString(Map<String, Object> args, String key) {
        Object v = args.get(key);
        return v instanceof String s ? s : null;
    }

    private Result<ToolOutput, ToolError> readFile(Path path) {
        if (!Files.exists(path)) {
            return Result.err(new ToolError.ValidationError("path", "File not found: " + path));
        }
        if (!Files.isReadable(path)) {
            return Result.err(new ToolError.ValidationError("path", "File not readable: " + path));
        }
        try {
            String content = Files.readString(path);
            return Result.ok(ToolOutput.of(content));
        } catch (IOException e) {
            return Result.err(new ToolError.ExecutionError("read_file", e.getMessage(), 1));
        }
    }

    private Result<ToolOutput, ToolError> writeFile(Path path, String content) {
        if (content == null) {
            return Result.err(new ToolError.ValidationError("content", "Content required for write_file"));
        }
        try {
            Path parent = path.getParent();
            if (parent != null && !Files.exists(parent)) {
                Files.createDirectories(parent);
            }
            Files.writeString(path, content, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
            return Result.ok(ToolOutput.of("File written successfully"));
        } catch (IOException e) {
            return Result.err(new ToolError.ExecutionError("write_file", e.getMessage(), 1));
        }
    }

    private Result<ToolOutput, ToolError> listDirectory(Path path) {
        if (!Files.exists(path) || !Files.isDirectory(path)) {
            return Result.err(new ToolError.ValidationError("path", "Not a directory: " + path));
        }
        try (Stream<Path> stream = Files.list(path)) {
            List<Map<String, Object>> entries = stream.map(p -> {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("name", p.getFileName().toString());
                m.put("type", Files.isDirectory(p) ? "directory" : "file");
                return m;
            }).collect(Collectors.toList());
            Map<String, Object> structured = new LinkedHashMap<>();
            structured.put("entries", entries);
            structured.put("count", entries.size());
            return Result.ok(new ToolOutput("Listed " + entries.size() + " entries", structured));
        } catch (IOException e) {
            return Result.err(new ToolError.ExecutionError("list_directory", e.getMessage(), 1));
        }
    }

    private Result<ToolOutput, ToolError> globSearch(Path path, String pattern) {
        if (pattern == null) {
            return Result.err(new ToolError.ValidationError("pattern", "Pattern required for glob_search"));
        }
        if (!Files.exists(path) || !Files.isDirectory(path)) {
            return Result.err(new ToolError.ValidationError("path", "Not a directory: " + path));
        }
        String syntax = "glob:" + pattern;
        PathMatcher matcher = FileSystems.getDefault().getPathMatcher(syntax);
        try (Stream<Path> stream = Files.walk(path)) {
            List<String> matches = stream
                .filter(p -> matcher.matches(p.getFileName()))
                .map(p -> path.relativize(p).toString())
                .collect(Collectors.toList());
            Map<String, Object> structured = new LinkedHashMap<>();
            structured.put("matches", matches);
            structured.put("count", matches.size());
            return Result.ok(new ToolOutput("Found " + matches.size() + " matches", structured));
        } catch (IOException e) {
            return Result.err(new ToolError.ExecutionError("glob_search", e.getMessage(), 1));
        }
    }

    private Result<ToolOutput, ToolError> fileInfo(Path path) {
        if (!Files.exists(path)) {
            return Result.err(new ToolError.ValidationError("path", "File not found: " + path));
        }
        try {
            Map<String, Object> info = new LinkedHashMap<>();
            info.put("name", path.getFileName().toString());
            info.put("absolutePath", path.toAbsolutePath().toString());
            info.put("size", Files.size(path));
            info.put("isDirectory", Files.isDirectory(path));
            info.put("isReadable", Files.isReadable(path));
            info.put("isWritable", Files.isWritable(path));
            info.put("lastModified", Files.getLastModifiedTime(path).toInstant().toString());
            return Result.ok(new ToolOutput("File info retrieved", info));
        } catch (IOException e) {
            return Result.err(new ToolError.ExecutionError("file_info", e.getMessage(), 1));
        }
    }
}
