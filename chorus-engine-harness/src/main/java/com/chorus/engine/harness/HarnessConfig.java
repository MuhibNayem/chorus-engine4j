package com.chorus.engine.harness;

import com.chorus.engine.agent.hitl.HitlGate;
import com.chorus.engine.core.checkpoint.Checkpointer;
import com.chorus.engine.core.checkpoint.InMemoryCheckpointer;
import com.chorus.engine.guardrails.TieredGuardrailEngine;
import com.chorus.engine.memory.ContextCompactor;
import com.chorus.engine.memory.hierarchical.HierarchicalMemoryManager;
import com.chorus.engine.telemetry.event.EventBus;
import com.chorus.engine.telemetry.event.InMemoryEventBus;
import com.chorus.engine.telemetry.metrics.BudgetEnforcer;
import com.chorus.engine.telemetry.metrics.MetricsCollector;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

import java.math.BigDecimal;
import java.nio.file.Path;
import java.time.Duration;

/**
 * Configuration for the harness. Immutable, builder-based.
 *
 * <p>All runtime dependencies (Checkpointer, MetricsCollector, etc.) are nullable —
 * the harness auto-creates sensible in-memory defaults when they are not provided.
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
    @NonNull ApprovalPolicy defaultApprovalPolicy,
    @Nullable Checkpointer checkpointer,
    @Nullable MetricsCollector metricsCollector,
    @Nullable TieredGuardrailEngine guardrailEngine,
    @Nullable BudgetEnforcer budgetEnforcer,
    @Nullable HitlGate hitlGate,
    @Nullable EventBus eventBus,
    @Nullable HierarchicalMemoryManager hierarchicalMemoryManager,
    @Nullable ContextCompactor contextCompactor,
    boolean enableSelfHealing,
    @NonNull Duration cacheTtl,
    int compactionThresholdTokens,
    int maxCacheEntries,
    @NonNull String compactionModel
) {
    public static final int DEFAULT_MAX_WORKERS = 8;
    public static final Duration DEFAULT_WORKER_TIMEOUT = Duration.ofMinutes(5);
    public static final Duration DEFAULT_TASK_TIMEOUT = Duration.ofMinutes(30);
    public static final double DEFAULT_CONFIDENCE_THRESHOLD = 0.55;
    public static final Duration DEFAULT_CACHE_TTL = Duration.ofMinutes(10);
    public static final int DEFAULT_COMPACTION_THRESHOLD = 60_000;
    public static final int DEFAULT_MAX_CACHE_ENTRIES = 100;
    public static final String DEFAULT_COMPACTION_MODEL = "claude-haiku-4-5-20251001";

    public HarnessConfig {
        if (maxConcurrentWorkers <= 0) maxConcurrentWorkers = DEFAULT_MAX_WORKERS;
        if (semanticConfidenceThreshold <= 0) semanticConfidenceThreshold = DEFAULT_CONFIDENCE_THRESHOLD;
        if (compactionThresholdTokens <= 0) compactionThresholdTokens = DEFAULT_COMPACTION_THRESHOLD;
        if (maxCacheEntries <= 0) maxCacheEntries = DEFAULT_MAX_CACHE_ENTRIES;
        if (compactionModel == null || compactionModel.isBlank()) compactionModel = DEFAULT_COMPACTION_MODEL;
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
            ApprovalPolicy.SUGGEST,
            new InMemoryCheckpointer(),
            new MetricsCollector(),
            null,
            null,
            null,
            new InMemoryEventBus(),
            null,
            null,
            false,
            DEFAULT_CACHE_TTL,
            DEFAULT_COMPACTION_THRESHOLD,
            DEFAULT_MAX_CACHE_ENTRIES,
            DEFAULT_COMPACTION_MODEL
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
        private Checkpointer checkpointer;
        private MetricsCollector metricsCollector;
        private TieredGuardrailEngine guardrailEngine;
        private BudgetEnforcer budgetEnforcer;
        private HitlGate hitlGate;
        private EventBus eventBus;
        private HierarchicalMemoryManager hierarchicalMemoryManager;
        private ContextCompactor contextCompactor;
        private boolean enableSelfHealing = false;
        private Duration cacheTtl = DEFAULT_CACHE_TTL;
        private int compactionThresholdTokens = DEFAULT_COMPACTION_THRESHOLD;
        private int maxCacheEntries = DEFAULT_MAX_CACHE_ENTRIES;
        private String compactionModel = DEFAULT_COMPACTION_MODEL;

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
        public Builder checkpointer(Checkpointer c) { this.checkpointer = c; return this; }
        public Builder metricsCollector(MetricsCollector m) { this.metricsCollector = m; return this; }
        public Builder guardrailEngine(TieredGuardrailEngine g) { this.guardrailEngine = g; return this; }
        public Builder budgetEnforcer(BudgetEnforcer b) { this.budgetEnforcer = b; return this; }
        public Builder hitlGate(HitlGate h) { this.hitlGate = h; return this; }
        public Builder eventBus(EventBus e) { this.eventBus = e; return this; }
        public Builder hierarchicalMemoryManager(HierarchicalMemoryManager m) { this.hierarchicalMemoryManager = m; return this; }
        public Builder contextCompactor(ContextCompactor c) { this.contextCompactor = c; return this; }
        public Builder enableSelfHealing(boolean b) { this.enableSelfHealing = b; return this; }
        public Builder cacheTtl(Duration d) { this.cacheTtl = d; return this; }
        public Builder compactionThresholdTokens(int t) { this.compactionThresholdTokens = t; return this; }
        public Builder maxCacheEntries(int n) { this.maxCacheEntries = n; return this; }
        public Builder compactionModel(String m) { this.compactionModel = m; return this; }

        public HarnessConfig build() {
            if (projectMemoryPath == null) throw new IllegalStateException("projectMemoryPath required");
            if (approvalLogPath == null) throw new IllegalStateException("approvalLogPath required");
            if (trajectoryLogPath == null) throw new IllegalStateException("trajectoryLogPath required");
            if (checkpointer == null) checkpointer = new InMemoryCheckpointer();
            if (metricsCollector == null) metricsCollector = new MetricsCollector();
            if (eventBus == null) eventBus = new InMemoryEventBus();
            return new HarnessConfig(
                projectMemoryPath, approvalLogPath, trajectoryLogPath,
                workerTimeout, taskTimeout, maxConcurrentWorkers,
                enableSemanticRouting, enableSafetyAudit, enableTimeTravel, enableResultCache,
                semanticConfidenceThreshold, defaultApprovalPolicy,
                checkpointer, metricsCollector, guardrailEngine, budgetEnforcer, hitlGate,
                eventBus, hierarchicalMemoryManager, contextCompactor,
                enableSelfHealing, cacheTtl, compactionThresholdTokens, maxCacheEntries,
                compactionModel
            );
        }
    }
}
