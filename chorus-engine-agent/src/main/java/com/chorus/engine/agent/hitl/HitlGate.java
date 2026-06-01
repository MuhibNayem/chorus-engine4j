package com.chorus.engine.agent.hitl;

import com.chorus.engine.core.result.Result;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Human-in-the-loop approval gate. Serializable to checkpoints.
 *
 * <p>Thread-safe. Supports per-gate and session-wide approvals.
 * Default timeout: 5 minutes.
 */
public final class HitlGate {

    private final Map<String, PendingGate> gates = new ConcurrentHashMap<>();
    private final Set<String> sessionApprovedTools = ConcurrentHashMap.newKeySet();
    private final Duration defaultTimeout;
    private final AtomicBoolean disposed = new AtomicBoolean(false);

    public HitlGate() {
        this(Duration.ofMinutes(5));
    }

    public HitlGate(@NonNull Duration defaultTimeout) {
        this.defaultTimeout = defaultTimeout;
    }

    /**
     * Request approval for a tool call. Blocks until resolved or timed out.
     */
    public @NonNull Result<HitlDecision, HitlError> requestApproval(
        @NonNull String runId,
        @NonNull String toolName,
        @NonNull Map<String, Object> arguments,
        @Nullable Duration timeout
    ) {
        return requestApprovalForGate(
            runId + ":" + toolName + ":" + System.nanoTime(),
            runId, toolName, arguments, timeout);
    }

    /**
     * Request approval using a caller-supplied gate ID so the emitted event and gate share the same key.
     */
    public @NonNull Result<HitlDecision, HitlError> requestApprovalForGate(
        @NonNull String gateId,
        @NonNull String runId,
        @NonNull String toolName,
        @NonNull Map<String, Object> arguments,
        @Nullable Duration timeout
    ) {
        if (disposed.get()) {
            return Result.err(new HitlError("GATE_DISPOSED", "HITL gate has been disposed", true));
        }

        if (sessionApprovedTools.contains(toolName)) {
            return Result.ok(HitlDecision.APPROVE_SESSION);
        }

        PendingGate gate = new PendingGate(gateId, runId, toolName, arguments, Instant.now());
        gates.put(gateId, gate);

        Duration effectiveTimeout = timeout != null ? timeout : defaultTimeout;

        try {
            HitlDecision decision = gate.future.get(effectiveTimeout.toMillis(), TimeUnit.MILLISECONDS);
            gates.remove(gateId);

            if (decision == HitlDecision.APPROVE_SESSION) {
                sessionApprovedTools.add(toolName);
            }
            if (decision == HitlDecision.REJECT && gate.rejectedReason != null) {
                return Result.err(new HitlError("REJECTED", gate.rejectedReason, false));
            }

            return Result.ok(decision);
        } catch (TimeoutException e) {
            gates.remove(gateId);
            gate.future.cancel(true);
            return Result.err(new HitlError("TIMEOUT", "Approval timed out after " + effectiveTimeout, false));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            gates.remove(gateId);
            return Result.err(new HitlError("INTERRUPTED", "Approval was interrupted", true));
        } catch (ExecutionException e) {
            gates.remove(gateId);
            return Result.err(new HitlError("ERROR", e.getCause().getMessage(), true));
        }
    }

    public boolean approve(@NonNull String gateId) {
        PendingGate gate = gates.get(gateId);
        if (gate != null) {
            gate.future.complete(HitlDecision.APPROVE);
            return true;
        }
        return false;
    }

    public boolean approveSession(@NonNull String gateId) {
        PendingGate gate = gates.get(gateId);
        if (gate != null) {
            sessionApprovedTools.add(gate.toolName);
            gate.future.complete(HitlDecision.APPROVE_SESSION);
            return true;
        }
        return false;
    }

    public boolean reject(@NonNull String gateId, @Nullable String reason) {
        PendingGate gate = gates.get(gateId);
        if (gate != null) {
            gate.rejectedReason = reason;
            gate.future.complete(HitlDecision.REJECT);
            return true;
        }
        return false;
    }

    /**
     * Returns the rejection reason recorded by the most recent {@link #reject} call
     * for the given gate, or {@code null} if the gate is unknown, was approved,
     * or no reason was provided.
     */
    public @Nullable String rejectionReason(@NonNull String gateId) {
        PendingGate gate = gates.get(gateId);
        return gate != null ? gate.rejectedReason : null;
    }

    /**
     * Reject all pending gates. Used during graceful shutdown.
     */
    public void dispose() {
        if (disposed.compareAndSet(false, true)) {
            for (PendingGate gate : gates.values()) {
                gate.future.complete(HitlDecision.REJECT);
            }
            gates.clear();
            sessionApprovedTools.clear();
        }
    }

    public boolean isDisposed() {
        return disposed.get();
    }

    public int pendingCount() {
        return gates.size();
    }

    public enum HitlDecision { APPROVE, APPROVE_SESSION, REJECT }

    public record HitlError(@NonNull String code, @NonNull String message, boolean fatal) {}

    private static final class PendingGate {
        final String gateId;
        final String runId;
        final String toolName;
        final Map<String, Object> arguments;
        final Instant createdAt;
        final CompletableFuture<HitlDecision> future = new CompletableFuture<>();
        
        volatile @Nullable String rejectedReason;


        PendingGate(String gateId, String runId, String toolName,
                    Map<String, Object> arguments, Instant createdAt) {
            this.gateId     = gateId;
            this.runId      = runId;
            this.toolName   = toolName;
            this.arguments  = Map.copyOf(arguments);
            this.createdAt  = createdAt;
        }
    }
}
