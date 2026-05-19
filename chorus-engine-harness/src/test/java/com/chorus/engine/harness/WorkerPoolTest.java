package com.chorus.engine.harness;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class WorkerPoolTest {

    @Test
    void registerAndCompleteWorkers() {
        WorkerPool pool = new WorkerPool();
        pool.setTaskId("task-1");

        List<WorkerAssignment> assignments = List.of(
            new WorkerAssignment("w1", WorkerRole.PLANNER, List.of(), "ctx-1", TaskStatus.QUEUED),
            new WorkerAssignment("w2", WorkerRole.CODER, List.of("workspace"), "ctx-1", TaskStatus.QUEUED)
        );

        List<WorkerAssignment> registered = pool.register(assignments);
        assertThat(registered).hasSize(2);
        assertThat(registered).extracting(WorkerAssignment::status).containsOnly(TaskStatus.QUEUED);

        pool.markRunning("w1");
        assertThat(pool.getAssignmentsByStatus(TaskStatus.RUNNING)).hasSize(1);

        pool.complete("w1", "done", List.of("file.java"), List.of("ok"), List.of(), List.of(),
            new WorkerResult.VerificationSummary(List.of(), List.of()));

        assertThat(pool.completedCount()).isEqualTo(1);
        assertThat(pool.allCompleted()).isFalse();

        pool.fail("w2", "failed", List.of("error"));
        assertThat(pool.failedCount()).isEqualTo(1);
        assertThat(pool.allCompleted()).isTrue();

        assertThat(pool.snapshotResults()).hasSize(2);
    }

    @Test
    void filterByRoleAndStatus() {
        WorkerPool pool = new WorkerPool();
        pool.register(List.of(
            new WorkerAssignment("w1", WorkerRole.PLANNER, List.of(), "ctx", TaskStatus.QUEUED),
            new WorkerAssignment("w2", WorkerRole.CODER, List.of(), "ctx", TaskStatus.QUEUED),
            new WorkerAssignment("w3", WorkerRole.REVIEWER, List.of(), "ctx", TaskStatus.QUEUED)
        ));

        assertThat(pool.getAssignmentsByRole(WorkerRole.CODER)).hasSize(1);
        assertThat(pool.getAssignmentsByRole(WorkerRole.PLANNER)).hasSize(1);
        assertThat(pool.getAssignmentsByStatus(TaskStatus.QUEUED)).hasSize(3);
    }
}
