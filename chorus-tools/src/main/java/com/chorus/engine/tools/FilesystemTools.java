package com.chorus.engine.tools;

import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.*;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Sandbox filesystem tools confined to a workspace root.
 */
@Component
public class FilesystemTools {

    private final Path workspaceRoot;

    public FilesystemTools() {
        this(Path.of(System.getProperty("user.dir")));
    }

    public FilesystemTools(Path workspaceRoot) {
        this.workspaceRoot = workspaceRoot.toAbsolutePath().normalize();
    }

    public String fileRead(String path, int offset, int limit) throws IOException {
        Path file = resolve(path);
        List<String> lines = Files.readAllLines(file);
        int start = Math.max(0, offset);
        int end = Math.min(lines.size(), start + Math.max(limit, 100));
        return String.join("\n", lines.subList(start, end));
    }

    public String fileWrite(String path, String content) throws IOException {
        Path file = resolve(path);
        Files.createDirectories(file.getParent());
        Path tmp = Path.of(file + ".tmp." + java.util.UUID.randomUUID());
        Files.writeString(tmp, content);
        Files.move(tmp, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        return "Written " + Files.size(file) + " bytes to " + path;
    }

    public String fileEdit(String path, String oldString, String newString) throws IOException {
        Path file = resolve(path);
        String content = Files.readString(file);
        if (!content.contains(oldString)) {
            return "Error: oldString not found in file";
        }
        String updated = content.replace(oldString, newString);
        Files.writeString(file, updated);
        return "Edited " + path;
    }

    public String listDir(String path) throws IOException {
        Path dir = resolve(path);
        try (Stream<Path> stream = Files.list(dir)) {
            return stream.map(p -> {
                String type = Files.isDirectory(p) ? "d" : "f";
                return type + " " + p.getFileName();
            }).collect(Collectors.joining("\n"));
        }
    }

    private Path resolve(String path) {
        Path resolved = workspaceRoot.resolve(path).normalize();
        if (!resolved.startsWith(workspaceRoot)) {
            throw new SecurityException("Path traversal attempt: " + path);
        }
        return resolved;
    }
}
