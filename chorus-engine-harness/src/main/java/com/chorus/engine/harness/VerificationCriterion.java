package com.chorus.engine.harness;

import org.jspecify.annotations.NonNull;

/**
 * A criterion that must be verified before a task is considered complete.
 */
public record VerificationCriterion(
    @NonNull String id,
    @NonNull String description
) {}
