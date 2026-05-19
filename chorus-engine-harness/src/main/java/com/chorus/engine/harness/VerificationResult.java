package com.chorus.engine.harness;

import org.jspecify.annotations.NonNull;

import java.util.List;

/**
 * Result of verifying a completed task against its criteria.
 */
public record VerificationResult(
    boolean ok,
    @NonNull List<String> findings
) {}
