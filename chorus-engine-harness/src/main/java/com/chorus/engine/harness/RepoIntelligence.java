package com.chorus.engine.harness;

import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

import java.time.Instant;
import java.util.List;

/**
 * Intelligence about a code repository — languages, package manager, test signals, etc.
 */
public record RepoIntelligence(
    @NonNull String version,
    @NonNull String summary,
    @Nullable String packageManager,
    @NonNull List<String> languages,
    @NonNull List<String> importantFiles,
    @NonNull List<String> commands,
    @NonNull List<String> testSignals,
    int sourceFileCount,
    @NonNull Instant generatedAt
) {}
