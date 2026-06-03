package com.chorus.engine.harness;

import org.jspecify.annotations.NonNull;

import java.util.List;

/**
 * Result of verifying a completed task against its criteria.
 */
public record VerificationResult(
    boolean ok,
    @NonNull List<String> findings
) {
    public @NonNull String summary() {
        return ok
            ? "PASS: " + String.join(", ", findings)
            : "FAIL: " + String.join(", ", findings);
    }
}
