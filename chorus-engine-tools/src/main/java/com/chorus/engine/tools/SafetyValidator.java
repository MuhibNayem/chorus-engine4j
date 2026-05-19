package com.chorus.engine.tools;

import org.jspecify.annotations.NonNull;

import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Pre-execution safety validation.
 *
 * <p>Defense-in-depth: blocks dangerous shell patterns, path traversal,
 * and suspicious URLs before any I/O occurs.
 */
public final class SafetyValidator {

    private static final List<Pattern> DANGEROUS_SHELL_PATTERNS = List.of(
        Pattern.compile("\\brm\\s+-rf\\b"),
        Pattern.compile("\\bsudo\\b"),
        Pattern.compile("\\bchmod\\s+777\\b"),
        Pattern.compile("\\bcurl\\s+.*\\|\\s*(sh|bash)\\b"),
        Pattern.compile("\\bwget\\s+.*\\|\\s*(sh|bash)\\b"),
        Pattern.compile(">\\s*/dev/sda"),
        Pattern.compile("\\bmkfs\\b"),
        Pattern.compile("\\bdd\\s+if=/dev/zero\\b"),
        Pattern.compile("\\b:\\(\\)\\{\\s*:\\|:\\&\\s*\\};:\\b"), // fork bomb
        Pattern.compile("\\beval\\s+\\$\\(")
    );

    private static final List<String> DANGEROUS_SUBSTRINGS = List.of(
        "rm -rf /",
        "rm -rf /*",
        "> /dev/sda",
        "mkfs.",
        "dd if=/dev/zero of=",
        "chmod 777 /",
        "curl | sh",
        "wget | bash",
        "sudo ",
        "eval $("
    );

    private SafetyValidator() {}

    /**
     * Validates a shell command against a blocklist of dangerous patterns.
     *
     * @return empty if safe, otherwise an error message describing the block
     */
    public static @NonNull Optional<String> validateShellCommand(@NonNull String command) {
        String lower = command.toLowerCase(Locale.ROOT);
        for (String sub : DANGEROUS_SUBSTRINGS) {
            if (lower.contains(sub.toLowerCase(Locale.ROOT))) {
                return Optional.of("Blocked dangerous pattern: " + sub);
            }
        }
        for (Pattern p : DANGEROUS_SHELL_PATTERNS) {
            if (p.matcher(lower).find()) {
                return Optional.of("Blocked dangerous pattern matching: " + p.pattern());
            }
        }
        return Optional.empty();
    }

    /**
     * Validates that a path stays within the given sandbox.
     *
     * @return empty if safe, otherwise an error message
     */
    public static @NonNull Optional<String> validatePath(@NonNull Path path, @NonNull Path sandbox) {
        String pathStr = path.toString();
        if (pathStr.contains("..")) {
            return Optional.of("Path traversal detected: " + path);
        }
        if (pathStr.startsWith("~")) {
            return Optional.of("Home directory reference not allowed: " + path);
        }
        Path normalizedSandbox;
        Path normalizedPath;
        try {
            normalizedSandbox = sandbox.toAbsolutePath().normalize();
            normalizedPath = path.toAbsolutePath().normalize();
        } catch (Exception e) {
            return Optional.of("Invalid path: " + e.getMessage());
        }
        if (!normalizedPath.startsWith(normalizedSandbox)) {
            return Optional.of("Path escapes sandbox: " + path);
        }
        return Optional.empty();
    }

    /**
     * Validates a URL against a blocklist of unsafe protocols and private addresses.
     *
     * @return empty if safe, otherwise an error message
     */
    public static @NonNull Optional<String> validateUrl(@NonNull String url) {
        String lower = url.toLowerCase(Locale.ROOT);
        if (lower.startsWith("file://")) {
            return Optional.of("file:// URLs are blocked");
        }
        if (lower.contains("localhost") || lower.contains("127.0.0.1") || lower.contains("0.0.0.0")) {
            return Optional.of("Localhost URLs are blocked");
        }
        if (lower.matches(".*\\b(10\\.\\d+\\.\\d+\\.\\d+|192\\.168\\.\\d+\\.\\d+|172\\.(1[6-9]|2\\d|3[01])\\.\\d+\\.\\d+)\\b.*")) {
            return Optional.of("Private IP addresses are blocked");
        }
        return Optional.empty();
    }
}
