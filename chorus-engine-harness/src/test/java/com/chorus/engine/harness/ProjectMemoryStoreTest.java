package com.chorus.engine.harness;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class ProjectMemoryStoreTest {

    @Test
    void recordDecision(@TempDir Path temp) {
        Path storage = temp.resolve("memory.json");
        ProjectMemoryStore store = new ProjectMemoryStore(storage, "/workspace");

        store.recordDecision("Use HikariCP");
        store.recordDecision("Java 25 with preview");

        ProjectMemory memory = store.get();
        assertThat(memory.decisions()).containsExactly("Use HikariCP", "Java 25 with preview");
        assertThat(memory.version()).isGreaterThan(0);
    }

    @Test
    void recordAndResolveKnownIssue(@TempDir Path temp) {
        Path storage = temp.resolve("memory.json");
        ProjectMemoryStore store = new ProjectMemoryStore(storage, "/workspace");

        store.recordKnownIssue("Race condition in cache");
        store.recordKnownIssue("Memory leak in parser");
        assertThat(store.get().knownIssues()).hasSize(2);

        store.resolveKnownIssue("Race condition in cache");
        assertThat(store.get().knownIssues()).containsExactly("Memory leak in parser");
    }

    @Test
    void recordCompletedTask(@TempDir Path temp) {
        Path storage = temp.resolve("memory.json");
        ProjectMemoryStore store = new ProjectMemoryStore(storage, "/workspace");

        store.recordCompletedTask("task-1", TaskKind.DEBUG, "Fixed NPE");

        ProjectMemory memory = store.get();
        assertThat(memory.completedTasks()).hasSize(1);
        assertThat(memory.completedTasks().get(0).taskId()).isEqualTo("task-1");
        assertThat(memory.completedTasks().get(0).kind()).isEqualTo(TaskKind.DEBUG);
    }

    @Test
    void persistence(@TempDir Path temp) {
        Path storage = temp.resolve("memory.json");
        ProjectMemoryStore store = new ProjectMemoryStore(storage, "/workspace");
        store.recordDecision("Persist me");

        ProjectMemoryStore store2 = new ProjectMemoryStore(storage, "/workspace");
        assertThat(store2.get().decisions()).contains("Persist me");
    }
}
