package com.chorus.engine.harness;

import org.jspecify.annotations.NonNull;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Logs every step of a harness execution for audit, safety analysis, and replay.
 * Each trajectory is a sequence of events stored as newline-delimited JSON.
 *
 * <p>Integrates with the telemetry module — trajectories can be exported to
 * OpenTelemetry spans for distributed tracing.
 */
public final class TrajectoryLogger {

    private final Path logPath;
    private final ObjectMapper mapper;
    private final Map<String, Trajectory> activeTrajectories = new ConcurrentHashMap<>();

    public TrajectoryLogger(@NonNull Path logPath) {
        this.logPath = logPath;
        this.mapper = new ObjectMapper();
    }

    public void startTrajectory(@NonNull String taskId, @NonNull TaskRecord task) {
        Trajectory trajectory = new Trajectory(taskId, Instant.now(), task);
        activeTrajectories.put(taskId, trajectory);
        appendEvent(taskId, Map.of(
            "type", "trajectory_start",
            "taskId", taskId,
            "taskKind", task.path().name(),
            "timestamp", Instant.now().toEpochMilli()
        ));
    }

    public void logEvent(@NonNull String taskId, @NonNull HarnessEvent event) {
        Map<String, Object> payload = switch (event) {
            case HarnessEvent.TaskClassified e -> Map.of(
                "type", "classified", "route", e.route().path().name(),
                "confidence", e.confidence(), "method", e.method().name());
            case HarnessEvent.WorkersAssigned e -> Map.of(
                "type", "workers_assigned", "count", e.assignments().size());
            case HarnessEvent.WorkerStarted e -> Map.of(
                "type", "worker_started", "workerId", e.workerId(), "role", e.role().name());
            case HarnessEvent.WorkerCompleted e -> Map.of(
                "type", "worker_completed", "workerId", e.workerId(),
                "status", e.result().status().name());
            case HarnessEvent.TaskVerified e -> Map.of(
                "type", "verified", "passed", e.passed(), "findings", e.findings());
            case HarnessEvent.TaskCompleted e -> Map.of(
                "type", "completed", "finalStatus", e.finalStatus().name(),
                "durationMs", e.durationMs(), "modelCalls", e.modelCalls());
            case HarnessEvent.CheckpointSaved e -> Map.of(
                "type", "checkpoint", "checkpointId", e.checkpointId(), "stage", e.stage().name());
            case HarnessEvent.ApprovalRequired e -> Map.of(
                "type", "approval_required", "tool", e.tool());
            case HarnessEvent.ErrorOccurred e -> Map.of(
                "type", "error", "errorType", e.errorType(), "message", e.message());
        };
        appendEvent(taskId, payload);
    }

    public void endTrajectory(@NonNull String taskId, @NonNull CompletedTaskExecution completed) {
        appendEvent(taskId, Map.of(
            "type", "trajectory_end",
            "taskId", taskId,
            "durationMs", completed.durationMs(),
            "modelCalls", completed.modelCalls(),
            "verificationOk", completed.verification().ok(),
            "timestamp", Instant.now().toEpochMilli()
        ));
        activeTrajectories.remove(taskId);
    }

    public boolean hasTrajectory(@NonNull String taskId) {
        return activeTrajectories.containsKey(taskId);
    }

    private void appendEvent(@NonNull String taskId, @NonNull Map<String, Object> event) {
        try {
            Files.createDirectories(logPath.getParent());
            String line = mapper.writeValueAsString(event) + "\n";
            Files.writeString(logPath, line, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException e) {
            // Never crash on trajectory logging
        }
    }

    private record Trajectory(@NonNull String taskId, @NonNull Instant startedAt, @NonNull TaskRecord task) {}
}
