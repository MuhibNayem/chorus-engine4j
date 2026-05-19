package com.chorus.engine.harness;

import org.jspecify.annotations.NonNull;

import java.nio.file.Path;
import java.time.Duration;

/**
 * Configuration for the harness. Immutable, builder-based.
 */
public record HarnessConfig(
    @NonNull Path projectMemoryPath,
    @NonNull Path approvalLogPath,
    @NonNull Path trajectoryLogPath,
    @NonNull Duration workerTimeout,
    @NonNull Duration taskTimeout,
    int maxConcurrentWorkers,
    boolean enableSemanticRouting,
    boolean enableSafetyAudit,
    boolean enableTimeTravel,
    boolean enableResultCache,
    double semanticConfidenceThreshold,
    @NonNull ApprovalPolicy defaultApprovalPolicy
) {
    public static final int DEFAULT_MAX_WORKERS = 8;
    public static final Duration DEFAULT_WORKER_TIMEOUT = Duration.ofMinutes(5);
    public static final Duration DEFAULT_TASK_TIMEOUT = Duration.ofMinutes(30);
    public static final double DEFAULT_CONFIDENCE_THRESHOLD = 0.55;

    public HarnessConfig {
        if (maxConcurrentWorkers <= 0) maxConcurrentWorkers = DEFAULT_MAX_WORKERS;
        if (semanticConfidenceThreshold <= 0) semanticConfidenceThreshold = DEFAULT_CONFIDENCE_THRESHOLD;
    }

    public static @NonNull Builder builder() {
        return new Builder();
    }

    public static @NonNull HarnessConfig defaults(@NonNull Path workspace) {
        return new HarnessConfig(
            workspace.resolve(".chorus/project-memory.json"),
            workspace.resolve(".chorus/approval-log.ndjson"),
            workspace.resolve(".chorus/trajectory.jsonl"),
            DEFAULT_WORKER_TIMEOUT,
            DEFAULT_TASK_TIMEOUT,
            DEFAULT_MAX_WORKERS,
            true, true, true, true,
            DEFAULT_CONFIDENCE_THRESHOLD,
            ApprovalPolicy.SUGGEST
        );
    }

    public static final class Builder {
        private Path projectMemoryPath;
        private Path approvalLogPath;
        private Path trajectoryLogPath;
        private Duration workerTimeout = DEFAULT_WORKER_TIMEOUT;
        private Duration taskTimeout = DEFAULT_TASK_TIMEOUT;
        private int maxConcurrentWorkers = DEFAULT_MAX_WORKERS;
        private boolean enableSemanticRouting = true;
        private boolean enableSafetyAudit = true;
        private boolean enableTimeTravel = true;
        private boolean enableResultCache = true;
        private double semanticConfidenceThreshold = DEFAULT_CONFIDENCE_THRESHOLD;
        private ApprovalPolicy defaultApprovalPolicy = ApprovalPolicy.SUGGEST;

        public Builder projectMemoryPath(Path p) { this.projectMemoryPath = p; return this; }
        public Builder approvalLogPath(Path p) { this.approvalLogPath = p; return this; }
        public Builder trajectoryLogPath(Path p) { this.trajectoryLogPath = p; return this; }
        public Builder workerTimeout(Duration d) { this.workerTimeout = d; return this; }
        public Builder taskTimeout(Duration d) { this.taskTimeout = d; return this; }
        public Builder maxConcurrentWorkers(int n) { this.maxConcurrentWorkers = n; return this; }
        public Builder enableSemanticRouting(boolean b) { this.enableSemanticRouting = b; return this; }
        public Builder enableSafetyAudit(boolean b) { this.enableSafetyAudit = b; return this; }
        public Builder enableTimeTravel(boolean b) { this.enableTimeTravel = b; return this; }
        public Builder enableResultCache(boolean b) { this.enableResultCache = b; return this; }
        public Builder semanticConfidenceThreshold(double d) { this.semanticConfidenceThreshold = d; return this; }
        public Builder defaultApprovalPolicy(ApprovalPolicy p) { this.defaultApprovalPolicy = p; return this; }

        public HarnessConfig build() {
            if (projectMemoryPath == null) throw new IllegalStateException("projectMemoryPath required");
            if (approvalLogPath == null) throw new IllegalStateException("approvalLogPath required");
            if (trajectoryLogPath == null) throw new IllegalStateException("trajectoryLogPath required");
            return new HarnessConfig(
                projectMemoryPath, approvalLogPath, trajectoryLogPath,
                workerTimeout, taskTimeout, maxConcurrentWorkers,
                enableSemanticRouting, enableSafetyAudit, enableTimeTravel, enableResultCache,
                semanticConfidenceThreshold, defaultApprovalPolicy
            );
        }
    }
}
