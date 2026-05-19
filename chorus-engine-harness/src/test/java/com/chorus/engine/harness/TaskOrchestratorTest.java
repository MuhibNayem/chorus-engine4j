package com.chorus.engine.harness;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.*;

class TaskOrchestratorTest {

    @Test
    void prepareSyncReturnsPreparedExecution() {
        RepoIntelligence repo = new RepoIntelligence(
            "1.0", "test", null,
            List.of(), List.of(), List.of(), List.of(), 0, java.time.Instant.now()
        );
        ProjectMemoryStore store = new ProjectMemoryStore(
            java.nio.file.Path.of(".").resolve("memory.json"),
            "."
        );
        TaskOrchestrator orchestrator = new TaskOrchestrator(null, repo, store);

        TaskOrchestrator.PrepareInput input = new TaskOrchestrator.PrepareInput(
            "What is 2+2?", "", "You are helpful."
        );
        TaskOrchestrator.PreparedTaskExecution result = orchestrator.prepareSync(input);

        assertThat(result.task()).isNotNull();
        assertThat(result.task().taskId()).isNotNull();
        assertThat(result.route()).isNotNull();
        assertThat(result.runtimePrompt()).isNotBlank();
        assertThat(result.workerAssignments()).isNotNull();
    }

    @Test
    void prepareWithSemanticRouter() {
        RepoIntelligence repo = new RepoIntelligence(
            "1.0", "test", null,
            List.of(), List.of(), List.of(), List.of(), 0, java.time.Instant.now()
        );
        ProjectMemoryStore store = new ProjectMemoryStore(
            java.nio.file.Path.of(".").resolve("memory.json"),
            "."
        );

        // Semantic router is null, so prepare falls back to prepareSync
        TaskOrchestrator orchestrator = new TaskOrchestrator(null, repo, store);

        TaskOrchestrator.PrepareInput input = new TaskOrchestrator.PrepareInput(
            "Fix the bug", "", "You are a coder."
        );
        TaskOrchestrator.PreparedTaskExecution result = orchestrator.prepare(input);

        assertThat(result.task()).isNotNull();
        assertThat(result.route().kind()).isNotNull();
    }

    @Test
    void prepareSyncCreatesWorkerAssignmentsForResearchTasks() {
        RepoIntelligence repo = new RepoIntelligence(
            "1.0", "test", null,
            List.of(), List.of(), List.of(), List.of(), 0, java.time.Instant.now()
        );
        ProjectMemoryStore store = new ProjectMemoryStore(
            java.nio.file.Path.of(".").resolve("memory.json"),
            "."
        );
        TaskOrchestrator orchestrator = new TaskOrchestrator(null, repo, store);

        TaskOrchestrator.PrepareInput input = new TaskOrchestrator.PrepareInput(
            "Search for best practices on error handling", "", "You are a researcher."
        );
        TaskOrchestrator.PreparedTaskExecution result = orchestrator.prepareSync(input);

        assertThat(result.workerAssignments()).isNotEmpty();
        assertThat(result.workerAssignments())
            .extracting(WorkerAssignment::role)
            .contains(WorkerRole.RESEARCHER, WorkerRole.PLANNER, WorkerRole.REVIEWER);
    }

    @Test
    void prepareSyncCreatesNoWorkersForDirectAgentPath() {
        RepoIntelligence repo = new RepoIntelligence(
            "1.0", "test", null,
            List.of(), List.of(), List.of(), List.of(), 0, java.time.Instant.now()
        );
        ProjectMemoryStore store = new ProjectMemoryStore(
            java.nio.file.Path.of(".").resolve("memory.json"),
            "."
        );
        TaskOrchestrator orchestrator = new TaskOrchestrator(null, repo, store);

        TaskOrchestrator.PrepareInput input = new TaskOrchestrator.PrepareInput(
            "What is 2+2?", "", "You are helpful."
        );
        TaskOrchestrator.PreparedTaskExecution result = orchestrator.prepareSync(input);

        assertThat(result.route().path()).isEqualTo(TaskPath.DIRECT_AGENT_PATH);
        assertThat(result.workerAssignments()).isEmpty();
    }

    @Test
    void nullRepoIntelligenceRejection() {
        ProjectMemoryStore store = new ProjectMemoryStore(
            java.nio.file.Path.of(".").resolve("memory.json"),
            "."
        );
        assertThatThrownBy(() -> new TaskOrchestrator(null, null, store))
            .isInstanceOf(NullPointerException.class);
    }

    @Test
    void nullProjectMemoryStoreRejection() {
        RepoIntelligence repo = new RepoIntelligence(
            "1.0", "test", null,
            List.of(), List.of(), List.of(), List.of(), 0, java.time.Instant.now()
        );
        assertThatThrownBy(() -> new TaskOrchestrator(null, repo, null))
            .isInstanceOf(NullPointerException.class);
    }

    @Test
    void nullInputRejectionOnPrepareSync() {
        RepoIntelligence repo = new RepoIntelligence(
            "1.0", "test", null,
            List.of(), List.of(), List.of(), List.of(), 0, java.time.Instant.now()
        );
        ProjectMemoryStore store = new ProjectMemoryStore(
            java.nio.file.Path.of(".").resolve("memory.json"),
            "."
        );
        TaskOrchestrator orchestrator = new TaskOrchestrator(null, repo, store);

        assertThatThrownBy(() -> orchestrator.prepareSync(null))
            .isInstanceOf(NullPointerException.class);
    }

    @Test
    void nullInputRejectionOnPrepare() {
        RepoIntelligence repo = new RepoIntelligence(
            "1.0", "test", null,
            List.of(), List.of(), List.of(), List.of(), 0, java.time.Instant.now()
        );
        ProjectMemoryStore store = new ProjectMemoryStore(
            java.nio.file.Path.of(".").resolve("memory.json"),
            "."
        );
        TaskOrchestrator orchestrator = new TaskOrchestrator(null, repo, store);

        assertThatThrownBy(() -> orchestrator.prepare(null))
            .isInstanceOf(NullPointerException.class);
    }
}
