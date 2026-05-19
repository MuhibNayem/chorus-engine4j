package com.chorus.engine.harness;

import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

import java.time.Instant;
import java.util.Map;

/**
 * Single entry in the approval audit log.
 */
public record ApprovalLogEntry(
    @NonNull Instant timestamp,
    @NonNull String tool,
    @NonNull Map<String, Object> args,
    @NonNull ApprovalDecision decision,
    @Nullable String sessionId
) {
    public enum ApprovalDecision {
        APPROVE,
        APPROVE_SESSION,
        DENY,
        SKIP
    }
}
