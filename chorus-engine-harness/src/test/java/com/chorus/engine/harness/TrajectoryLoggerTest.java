package com.chorus.engine.harness;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.*;

class TrajectoryLoggerTest {

    @Test
    void logEventAndHasTrajectory(@TempDir Path tempDir) {
        Path logPath = tempDir.resolve("trajectory.jsonl");
        TrajectoryLogger logger = new TrajectoryLogger(logPath);

        TaskRecord task = new TaskRecord(
            "task-1", WorkerRole.ORCHESTRATOR, ExecutionLane.FOREGROUND_SYNC,
            TaskPath.DIRECT_AGENT_PATH, TaskStatus.RUNNING,
            Instant.now(), Instant.now(), List.of()
        );

        logger.startTrajectory("task-1", task);
        assertThat(logger.hasTrajectory("task-1")).isTrue();

        HarnessEvent event = new HarnessEvent.TaskClassified(
            Instant.now(), "task-1",
            new TaskRoute(TaskKind.ANSWER_ONLY, ExecutionLane.CHEAP_TRIAGE,
                TaskPath.DIRECT_AGENT_PATH, false, false, true),
            0.0, SemanticTaskRouter.RoutingMethod.FALLBACK
        );
        logger.logEvent("task-1", event);

        CompletedTaskExecution completed = new CompletedTaskExecution(
            new TaskRecord("task-1", WorkerRole.ORCHESTRATOR, ExecutionLane.FOREGROUND_SYNC,
                TaskPath.DIRECT_AGENT_PATH, TaskStatus.COMPLETED,
                Instant.now(), Instant.now(), List.of()),
            new VerificationResult(true, List.of()),
            1, 100
        );
        logger.endTrajectory("task-1", completed);
        assertThat(logger.hasTrajectory("task-1")).isFalse();
    }

    @Test
    void persistenceRoundTrip(@TempDir Path tempDir) throws Exception {
        Path logPath = tempDir.resolve("trajectory.jsonl");
        TrajectoryLogger logger = new TrajectoryLogger(logPath);

        TaskRecord task = new TaskRecord(
            "task-2", WorkerRole.ORCHESTRATOR, ExecutionLane.FOREGROUND_SYNC,
            TaskPath.DIRECT_AGENT_PATH, TaskStatus.RUNNING,
            Instant.now(), Instant.now(), List.of()
        );

        logger.startTrajectory("task-2", task);
        logger.logEvent("task-2", new HarnessEvent.TaskVerified(
            Instant.now(), "task-2", true, List.of("all good")
        ));
        logger.endTrajectory("task-2", new CompletedTaskExecution(
            new TaskRecord("task-2", WorkerRole.ORCHESTRATOR, ExecutionLane.FOREGROUND_SYNC,
                TaskPath.DIRECT_AGENT_PATH, TaskStatus.COMPLETED,
                Instant.now(), Instant.now(), List.of()),
            new VerificationResult(true, List.of()),
            1, 200
        ));

        assertThat(Files.exists(logPath)).isTrue();
        List<String> lines = Files.readAllLines(logPath);
        assertThat(lines).hasSizeGreaterThanOrEqualTo(3);

        // Verify each line is valid JSON
        com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
        for (String line : lines) {
            assertThat(line).isNotBlank();
            mapper.readTree(line);
        }
    }

    @Test
    void nullTaskIdRejectionOnStart() {
        TrajectoryLogger logger = new TrajectoryLogger(Path.of("/dev/null"));
        TaskRecord task = new TaskRecord(
            "t", WorkerRole.ORCHESTRATOR, ExecutionLane.FOREGROUND_SYNC,
            TaskPath.DIRECT_AGENT_PATH, TaskStatus.RUNNING,
            Instant.now(), Instant.now(), List.of()
        );

        assertThatThrownBy(() -> logger.startTrajectory(null, task))
            .isInstanceOf(NullPointerException.class);
    }

    @Test
    void nullTaskRejectionOnStart() {
        TrajectoryLogger logger = new TrajectoryLogger(Path.of("/dev/null"));
        assertThatThrownBy(() -> logger.startTrajectory("task", null))
            .isInstanceOf(NullPointerException.class);
    }

    @Test
    void nullEventRejectionOnLog() {
        TrajectoryLogger logger = new TrajectoryLogger(Path.of("/dev/null"));
        assertThatThrownBy(() -> logger.logEvent("task", null))
            .isInstanceOf(NullPointerException.class);
    }

    @Test
    void nullTaskIdRejectionOnEnd() {
        TrajectoryLogger logger = new TrajectoryLogger(Path.of("/dev/null"));
        CompletedTaskExecution completed = new CompletedTaskExecution(
            new TaskRecord("t", WorkerRole.ORCHESTRATOR, ExecutionLane.FOREGROUND_SYNC,
                TaskPath.DIRECT_AGENT_PATH, TaskStatus.COMPLETED,
                Instant.now(), Instant.now(), List.of()),
            new VerificationResult(true, List.of()),
            0, 0
        );
        assertThatThrownBy(() -> logger.endTrajectory(null, completed))
            .isInstanceOf(NullPointerException.class);
    }

    @Test
    void nullCompletedRejectionOnEnd() {
        TrajectoryLogger logger = new TrajectoryLogger(Path.of("/dev/null"));
        assertThatThrownBy(() -> logger.endTrajectory("task", null))
            .isInstanceOf(NullPointerException.class);
    }
}
