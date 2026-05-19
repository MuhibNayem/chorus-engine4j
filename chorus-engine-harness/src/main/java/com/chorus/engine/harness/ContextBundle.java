package com.chorus.engine.harness;

import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

/**
 * Immutable context bundle passed to workers during execution.
 */
public record ContextBundle(
    @NonNull String id,
    @NonNull String prefixHash,
    @NonNull String taskDelta,
    @NonNull String repoFactsVersion,
    @Nullable String compactionRef,
    @NonNull String toolSchemaVersion
) {}
