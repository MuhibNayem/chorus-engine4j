package com.chorus.engine.harness;

import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

import java.io.IOException;
import java.nio.file.FileVisitOption;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;

/**
 * Detects repository characteristics — languages, package manager, test framework, etc.
 * Scans the filesystem heuristically with bounded depth.
 */
public final class RepoIntelligenceDetector {

    private static final int MAX_SCAN_DEPTH = 6;
    private static final int MAX_SOURCE_FILES = 10_000;

    private final Path root;

    public RepoIntelligenceDetector(@NonNull Path root) {
        this.root = root.toAbsolutePath().normalize();
    }

    public @NonNull RepoIntelligence detect() {
        String packageManager = detectPackageManager();
        List<String> languages = detectLanguages();
        List<String> importantFiles = detectImportantFiles();
        List<String> commands = detectCommands();
        List<String> testSignals = detectTestSignals();
        int sourceFiles = countSourceFiles();

        String langStr = languages.isEmpty() ? "unknown" : String.join("/", languages);
        String summary = langStr + " project with " + sourceFiles + " source files."
            + (packageManager != null ? " Package manager: " + packageManager + "." : "");

        return new RepoIntelligence(
            "v1-" + System.currentTimeMillis(),
            summary,
            packageManager,
            languages,
            importantFiles,
            commands,
            testSignals,
            sourceFiles,
            Instant.now()
        );
    }

    private @Nullable String detectPackageManager() {
        if (exists("pnpm-lock.yaml")) return "pnpm";
        if (exists("yarn.lock")) return "yarn";
        if (exists("bun.lockb")) return "bun";
        if (exists("package-lock.json")) return "npm";
        if (exists("Cargo.toml")) return "cargo";
        if (exists("go.mod")) return "go";
        if (exists("pom.xml")) return "maven";
        if (exists("build.gradle") || exists("build.gradle.kts")) return "gradle";
        if (exists("pyproject.toml") || exists("requirements.txt")) return "pip";
        return null;
    }

    private @NonNull List<String> detectLanguages() {
        List<String> langs = new ArrayList<>();
        if (exists("tsconfig.json") && !langs.contains("TypeScript")) langs.add("TypeScript");
        if (exists("package.json") && !langs.contains("JavaScript")) langs.add("JavaScript");
        if (exists("Cargo.toml") && !langs.contains("Rust")) langs.add("Rust");
        if (exists("go.mod") && !langs.contains("Go")) langs.add("Go");
        if (exists("pom.xml") && !langs.contains("Java")) langs.add("Java");
        if (exists("build.gradle") || exists("build.gradle.kts")) {
            if (!langs.contains("Java")) langs.add("Java");
            if (!langs.contains("Kotlin")) langs.add("Kotlin");
        }
        if ((exists("pyproject.toml") || exists("requirements.txt")) && !langs.contains("Python")) {
            langs.add("Python");
        }
        if (countGlob("*.rs") > 0 && !langs.contains("Rust")) langs.add("Rust");
        return langs;
    }

    private @NonNull List<String> detectImportantFiles() {
        String[] candidates = {
            "README.md", "CLAUDE.md", "AGENTS.md", "CONTRIBUTING.md",
            "package.json", "tsconfig.json", "Cargo.toml", "go.mod",
            "pyproject.toml", ".env.example", "pom.xml", "build.gradle", "build.gradle.kts",
            "Dockerfile", "docker-compose.yml", "LICENSE", ".gitignore"
        };
        List<String> found = new ArrayList<>();
        for (String f : candidates) {
            if (exists(f)) found.add(f);
        }
        return found;
    }

    private @NonNull List<String> detectCommands() {
        List<String> cmds = new ArrayList<>();
        Path pkgJson = root.resolve("package.json");
        if (Files.exists(pkgJson)) {
            try {
                String content = Files.readString(pkgJson);
                // Simple heuristic — extract script names from package.json
                int scriptsIdx = content.indexOf("\"scripts\"");
                if (scriptsIdx >= 0) {
                    int braceStart = content.indexOf('{', scriptsIdx);
                    int braceEnd = content.indexOf('}', braceStart);
                    if (braceStart >= 0 && braceEnd >= 0) {
                        String scriptsBlock = content.substring(braceStart + 1, braceEnd);
                        for (String line : scriptsBlock.split(",")) {
                            int quoteIdx = line.indexOf('"');
                            if (quoteIdx >= 0) {
                                int endQuote = line.indexOf('"', quoteIdx + 1);
                                if (endQuote > quoteIdx) {
                                    String key = line.substring(quoteIdx + 1, endQuote);
                                    if (!key.isBlank() && cmds.size() < 8) {
                                        cmds.add("npm run " + key.trim());
                                    }
                                }
                            }
                        }
                    }
                }
            } catch (IOException ignored) {
            }
        }
        if (exists("pom.xml") || exists("build.gradle") || exists("build.gradle.kts")) {
            cmds.add("./gradlew test");
            cmds.add("./gradlew build");
        }
        if (exists("Cargo.toml")) {
            cmds.add("cargo test");
            cmds.add("cargo build");
        }
        return cmds;
    }

    private @NonNull List<String> detectTestSignals() {
        List<String> signals = new ArrayList<>();
        if (exists("vitest.config.ts") || exists("vitest.config.js")) signals.add("vitest");
        if (exists("jest.config.js") || exists("jest.config.ts")) signals.add("jest");
        if (exists("pom.xml") || exists("build.gradle") || exists("build.gradle.kts")) {
            signals.add("junit");
        }
        if (exists("Cargo.toml")) signals.add("cargo-test");
        if (exists("go.mod")) signals.add("go-test");
        if (countGlob("*test*.py") > 0 || countGlob("test_*.py") > 0) signals.add("pytest");
        return signals;
    }

    private int countSourceFiles() {
        Counter counter = new Counter();
        try {
            Files.walkFileTree(root, EnumSet.noneOf(FileVisitOption.class), MAX_SCAN_DEPTH,
                new SimpleFileVisitor<>() {
                    @Override
                    public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                        String name = dir.getFileName() != null ? dir.getFileName().toString() : "";
                        if (name.startsWith(".") || name.equals("node_modules")
                            || name.equals("dist") || name.equals("build")
                            || name.equals("target") || name.equals("__pycache__")
                            || name.equals(".gradle")) {
                            return FileVisitResult.SKIP_SUBTREE;
                        }
                        return FileVisitResult.CONTINUE;
                    }

                    @Override
                    public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                        if (counter.count >= MAX_SOURCE_FILES) {
                            return FileVisitResult.TERMINATE;
                        }
                        String name = file.getFileName().toString();
                        if (name.endsWith(".ts") || name.endsWith(".tsx")
                            || name.endsWith(".js") || name.endsWith(".jsx")
                            || name.endsWith(".py") || name.endsWith(".rs")
                            || name.endsWith(".go") || name.endsWith(".java")
                            || name.endsWith(".kt") || name.endsWith(".scala")) {
                            counter.count++;
                        }
                        return FileVisitResult.CONTINUE;
                    }
                });
        } catch (IOException ignored) {
        }
        return counter.count;
    }

    private long countGlob(@NonNull String glob) {
        try (var stream = Files.newDirectoryStream(root, glob)) {
            long count = 0;
            for (var entry : stream) {
                count++;
            }
            return count;
        } catch (IOException e) {
            return 0;
        }
    }

    private boolean exists(@NonNull String path) {
        return Files.exists(root.resolve(path));
    }

    private static final class Counter {
        int count;
    }
}
