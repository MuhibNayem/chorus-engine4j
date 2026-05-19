package com.chorus.engine.harness;

import org.jspecify.annotations.NonNull;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Manages worker assignments and results for a harness task.
 * Thread-safe — supports concurrent worker execution.
 */
public final class WorkerPool {

    private final Map<String, WorkerAssignment> assignments = new ConcurrentHashMap<>();
    private final Map<String, WorkerResult> results = new ConcurrentHashMap<>();
    private final AtomicReference<String> currentTaskId = new AtomicReference<>();

    /**
     * Register a batch of worker assignments.
     *
     * @return a snapshot of all registered assignments
     */
    public @NonNull List<WorkerAssignment> register(@NonNull List<WorkerAssignment> newAssignments) {
        for (WorkerAssignment assignment : newAssignments) {
            assignments.put(assignment.workerId(),
                new WorkerAssignment(
                    assignment.workerId(),
                    assignment.role(),
                    assignment.ownedScope(),
                    assignment.inputBundleId(),
                    TaskStatus.QUEUED
                ));
        }
        return snapshotAssignments();
    }

    public void markRunning(@NonNull String workerId) {
        updateStatus(workerId, TaskStatus.RUNNING);
    }

    public void markBlocked(@NonNull String workerId) {
        updateStatus(workerId, TaskStatus.BLOCKED);
    }

    public void markVerifying(@NonNull String workerId) {
        updateStatus(workerId, TaskStatus.VERIFYING);
    }

    public void complete(
        @NonNull String workerId,
        @NonNull String summary,
        @NonNull List<String> changedFiles,
        @NonNull List<String> findings,
        @NonNull List<String> risks,
        @NonNull List<String> nextActions,
        WorkerResult.VerificationSummary verification
    ) {
        updateStatus(workerId, TaskStatus.COMPLETED);
        results.put(workerId, new WorkerResult(
            workerId,
            TaskStatus.COMPLETED,
            summary,
            changedFiles,
            findings,
            risks,
            nextActions,
            verification
        ));
    }

    public void fail(@NonNull String workerId, @NonNull String summary, @NonNull List<String> findings) {
        updateStatus(workerId, TaskStatus.FAILED);
        results.put(workerId, new WorkerResult(
            workerId,
            TaskStatus.FAILED,
            summary,
            List.of(),
            findings,
            List.of(),
            List.of(),
            new WorkerResult.VerificationSummary(List.of(), List.of())
        ));
    }

    public @NonNull List<WorkerAssignment> snapshotAssignments() {
        return List.copyOf(assignments.values());
    }

    public @NonNull List<WorkerResult> snapshotResults() {
        return List.copyOf(results.values());
    }

    public @NonNull List<WorkerAssignment> getAssignmentsByRole(@NonNull WorkerRole role) {
        return assignments.values().stream()
            .filter(a -> a.role() == role)
            .toList();
    }

    public @NonNull List<WorkerAssignment> getAssignmentsByStatus(@NonNull TaskStatus status) {
        return assignments.values().stream()
            .filter(a -> a.status() == status)
            .toList();
    }

    public boolean allCompleted() {
        return !assignments.isEmpty()
            && assignments.values().stream()
                .allMatch(a -> a.status() == TaskStatus.COMPLETED || a.status() == TaskStatus.FAILED);
    }

    public int completedCount() {
        return (int) assignments.values().stream()
            .filter(a -> a.status() == TaskStatus.COMPLETED)
            .count();
    }

    public int failedCount() {
        return (int) assignments.values().stream()
            .filter(a -> a.status() == TaskStatus.FAILED)
            .count();
    }

    public void setTaskId(@NonNull String taskId) {
        this.currentTaskId.set(taskId);
    }

    public @NonNull String getTaskId() {
        String id = currentTaskId.get();
        return id != null ? id : "unknown";
    }

    private void updateStatus(@NonNull String workerId, @NonNull TaskStatus status) {
        WorkerAssignment existing = assignments.get(workerId);
        if (existing != null) {
            assignments.put(workerId, new WorkerAssignment(
                existing.workerId(),
                existing.role(),
                existing.ownedScope(),
                existing.inputBundleId(),
                status
            ));
        }
    }
}
