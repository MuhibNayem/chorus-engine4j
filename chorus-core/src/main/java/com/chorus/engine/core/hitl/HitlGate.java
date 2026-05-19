package com.chorus.engine.core.hitl;

import com.chorus.engine.core.ApprovalPolicy;
import com.chorus.engine.core.event.ChatMessage;
import com.chorus.engine.core.event.HitlDecision;

import java.time.Duration;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledFuture;

public class HitlGate {

    private static final Set<String> DEFAULT_SENSITIVE_TOOLS = Set.of(
        "file_write", "file_edit", "run_command", "git_commit", "delegate_to_subagent"
    );

    private final Set<String> sensitiveTools;
    private final Duration defaultTimeout;
    private final Map<String, GateEntry> gates = new ConcurrentHashMap<>();
    private final Set<String> sessionApproved = ConcurrentHashMap.newKeySet();
    private final Map<String, HitlDecision> pending = new ConcurrentHashMap<>();
    private volatile boolean disposed = false;

    public HitlGate() {
        this(Set.of(), Set.of(), Duration.ofMinutes(5));
    }

    public HitlGate(Set<String> sensitiveTools, Set<String> additionalSensitiveTools, Duration defaultTimeout) {
        if (sensitiveTools.isEmpty()) {
            this.sensitiveTools = ConcurrentHashMap.newKeySet();
            this.sensitiveTools.addAll(DEFAULT_SENSITIVE_TOOLS);
            this.sensitiveTools.addAll(additionalSensitiveTools);
        } else {
            this.sensitiveTools = ConcurrentHashMap.newKeySet();
            this.sensitiveTools.addAll(sensitiveTools);
        }
        this.defaultTimeout = defaultTimeout;
    }

    public boolean shouldPause(java.util.List<ChatMessage.ToolCall> toolCalls, ApprovalPolicy policy) {
        if (policy == ApprovalPolicy.FULL_AUTO || policy == ApprovalPolicy.SUGGEST) {
            return false;
        }
        return toolCalls.stream().anyMatch(tc -> {
            String name = tc.name();
            return (sensitiveTools.contains(name) || name.startsWith("mcp__"))
                && !sessionApproved.contains(name);
        });
    }

    public CompletableFuture<HitlDecision> waitForDecision(String resumeKey) {
        return waitForDecision(resumeKey, defaultTimeout);
    }

    public CompletableFuture<HitlDecision> waitForDecision(String resumeKey, Duration timeout) {
        if (disposed) {
            return CompletableFuture.failedFuture(new HitlGateDisposedException());
        }
        HitlDecision queued = pending.remove(resumeKey);
        if (queued != null) {
            return CompletableFuture.completedFuture(queued);
        }
        CompletableFuture<HitlDecision> future = new CompletableFuture<>();
        GateEntry entry = new GateEntry(future);
        gates.put(resumeKey, entry);
        if (!timeout.isNegative() && !timeout.isZero()) {
            entry.setTimer(java.util.concurrent.Executors.newSingleThreadScheduledExecutor()
                .schedule(() -> {
                    gates.remove(resumeKey);
                    future.completeExceptionally(new HitlGateTimeoutException(resumeKey));
                }, timeout.toMillis(), java.util.concurrent.TimeUnit.MILLISECONDS));
        }
        return future;
    }

    public void resolve(String resumeKey, HitlDecision decision) {
        if (decision instanceof HitlDecision.ApproveSession approveSession) {
            if (approveSession.toolNames() != null) {
                sessionApproved.addAll(approveSession.toolNames());
            }
        }
        GateEntry entry = gates.remove(resumeKey);
        HitlDecision normalized = decision instanceof HitlDecision.ApproveSession
            ? new HitlDecision.Approve()
            : decision;
        if (entry == null) {
            pending.put(resumeKey, normalized);
            return;
        }
        if (entry.getTimer() != null) {
            entry.getTimer().cancel(false);
        }
        entry.getFuture().complete(normalized);
    }

    public void dispose() {
        if (disposed) return;
        disposed = true;
        HitlGateDisposedException err = new HitlGateDisposedException();
        for (GateEntry entry : gates.values()) {
            if (entry.getTimer() != null) {
                entry.getTimer().cancel(false);
            }
            entry.getFuture().completeExceptionally(err);
        }
        gates.clear();
        pending.clear();
    }

    public void resetSessionApprovals() {
        sessionApproved.clear();
        pending.clear();
    }

    private static class GateEntry {
        private final CompletableFuture<HitlDecision> future;
        private ScheduledFuture<?> timer;

        GateEntry(CompletableFuture<HitlDecision> future) {
            this.future = future;
        }

        CompletableFuture<HitlDecision> getFuture() { return future; }
        ScheduledFuture<?> getTimer() { return timer; }
        void setTimer(ScheduledFuture<?> timer) { this.timer = timer; }
    }

    public static class HitlGateTimeoutException extends RuntimeException {
        public HitlGateTimeoutException(String resumeKey) {
            super("HitlGate timed out waiting for approval of \"" + resumeKey + "\".");
        }
    }

    public static class HitlGateDisposedException extends RuntimeException {
        public HitlGateDisposedException() {
            super("HitlGate was disposed before a decision was made.");
        }
    }
}
