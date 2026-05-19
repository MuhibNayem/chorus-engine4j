package com.chorus.engine.harness;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class SafetyAuditorTest {

    SafetyAuditor auditor = new SafetyAuditor();

    @Test
    void preExecutionSafe() {
        TaskRecord task = new TaskRecord("t1", WorkerRole.ORCHESTRATOR, ExecutionLane.FOREGROUND_SYNC,
            TaskPath.DIRECT_AGENT_PATH, TaskStatus.RUNNING, Instant.now(), Instant.now(), List.of());
        SafetyAuditor.SafetyReport report = auditor.auditPreExecution(task, "Hello world");
        assertThat(report.safe()).isTrue();
        assertThat(report.checks()).allMatch(SafetyAuditor.SafetyCheck::passed);
    }

    @Test
    void preExecutionInputTooLong() {
        TaskRecord task = new TaskRecord("t1", WorkerRole.ORCHESTRATOR, ExecutionLane.FOREGROUND_SYNC,
            TaskPath.DIRECT_AGENT_PATH, TaskStatus.RUNNING, Instant.now(), Instant.now(), List.of());
        String huge = "x".repeat(200_000);
        SafetyAuditor.SafetyReport report = auditor.auditPreExecution(task, huge);
        assertThat(report.safe()).isFalse();
        assertThat(report.checks()).anyMatch(c -> c.id().equals("input-length") && !c.passed());
    }

    @Test
    void preExecutionSensitivePathDetected() {
        TaskRecord task = new TaskRecord("t1", WorkerRole.ORCHESTRATOR, ExecutionLane.FOREGROUND_SYNC,
            TaskPath.DIRECT_AGENT_PATH, TaskStatus.RUNNING, Instant.now(), Instant.now(), List.of());
        SafetyAuditor.SafetyReport report = auditor.auditPreExecution(task,
            "Read the file at /home/user/.ssh/id_rsa");
        assertThat(report.safe()).isFalse();
        assertThat(report.checks()).anyMatch(c -> c.id().equals("sensitive-paths") && !c.passed());
    }

    @Test
    void postExecutionTooManyToolCalls() {
        TaskRecord task = new TaskRecord("t1", WorkerRole.ORCHESTRATOR, ExecutionLane.FOREGROUND_SYNC,
            TaskPath.DIRECT_AGENT_PATH, TaskStatus.RUNNING, Instant.now(), Instant.now(), List.of());
        SafetyAuditor.SafetyReport report = auditor.auditPostExecution(task, "Done", 200, List.of());
        assertThat(report.safe()).isFalse();
        assertThat(report.checks()).anyMatch(c -> c.id().equals("tool-call-limit") && !c.passed());
    }

    @Test
    void postExecutionTooManyFailures() {
        TaskRecord task = new TaskRecord("t1", WorkerRole.ORCHESTRATOR, ExecutionLane.FOREGROUND_SYNC,
            TaskPath.DIRECT_AGENT_PATH, TaskStatus.RUNNING, Instant.now(), Instant.now(), List.of());
        List<WorkerResult> results = List.of(
            new WorkerResult("w1", TaskStatus.FAILED, "err", List.of(), List.of("err"), List.of(), List.of(),
                new WorkerResult.VerificationSummary(List.of(), List.of())),
            new WorkerResult("w2", TaskStatus.FAILED, "err", List.of(), List.of("err"), List.of(), List.of(),
                new WorkerResult.VerificationSummary(List.of(), List.of())),
            new WorkerResult("w3", TaskStatus.COMPLETED, "ok", List.of(), List.of(), List.of(), List.of(),
                new WorkerResult.VerificationSummary(List.of(), List.of()))
        );
        SafetyAuditor.SafetyReport report = auditor.auditPostExecution(task, "Done", 0, results);
        assertThat(report.safe()).isFalse();
        assertThat(report.checks()).anyMatch(c -> c.id().equals("worker-failures") && !c.passed());
    }
}
