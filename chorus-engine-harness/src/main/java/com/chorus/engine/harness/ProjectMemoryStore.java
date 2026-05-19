package com.chorus.engine.harness;

import org.jspecify.annotations.NonNull;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Persistent store for project memory — decisions, known issues, completed tasks.
 * Backed by a JSON file on disk. Thread-safe via copy-on-write.
 */
public final class ProjectMemoryStore {

    private final Path storagePath;
    private final ObjectMapper mapper;
    private final AtomicReference<ProjectMemory> memoryRef;
    private final String workspace;

    public ProjectMemoryStore(@NonNull Path storagePath, @NonNull String workspace) {
        this.storagePath = storagePath;
        this.workspace = workspace;
        this.mapper = new ObjectMapper().registerModule(new JavaTimeModule());
        this.memoryRef = new AtomicReference<>(load());
    }

    public @NonNull ProjectMemory get() {
        return memoryRef.get();
    }

    public synchronized void recordDecision(@NonNull String decision) {
        ProjectMemory current = memoryRef.get();
        List<String> decisions = new ArrayList<>(current.decisions());
        decisions.add(decision);
        update(current, decisions, current.knownIssues(), current.completedTasks());
    }

    public synchronized void recordKnownIssue(@NonNull String issue) {
        ProjectMemory current = memoryRef.get();
        List<String> issues = new ArrayList<>(current.knownIssues());
        if (!issues.contains(issue)) {
            issues.add(issue);
            update(current, current.decisions(), issues, current.completedTasks());
        }
    }

    public synchronized void recordCompletedTask(
        @NonNull String taskId,
        @NonNull TaskKind kind,
        @NonNull String summary
    ) {
        ProjectMemory current = memoryRef.get();
        List<ProjectMemory.CompletedTaskSummary> tasks = new ArrayList<>(current.completedTasks());
        tasks.add(new ProjectMemory.CompletedTaskSummary(taskId, kind, summary, Instant.now()));
        update(current, current.decisions(), current.knownIssues(), tasks);
    }

    public synchronized void resolveKnownIssue(@NonNull String issue) {
        ProjectMemory current = memoryRef.get();
        List<String> issues = new ArrayList<>(current.knownIssues());
        if (issues.remove(issue)) {
            update(current, current.decisions(), issues, current.completedTasks());
        }
    }

    private void update(
        ProjectMemory current,
        List<String> decisions,
        List<String> knownIssues,
        List<ProjectMemory.CompletedTaskSummary> completedTasks
    ) {
        ProjectMemory updated = new ProjectMemory(
            current.version() + 1,
            workspace,
            List.copyOf(decisions),
            List.copyOf(knownIssues),
            List.copyOf(completedTasks),
            Instant.now()
        );
        memoryRef.set(updated);
        persist(updated);
    }

    private @NonNull ProjectMemory load() {
        if (!Files.exists(storagePath)) {
            return emptyMemory();
        }
        try {
            String json = Files.readString(storagePath);
            return mapper.readValue(json, new TypeReference<>() {});
        } catch (IOException e) {
            return emptyMemory();
        }
    }

    private void persist(@NonNull ProjectMemory memory) {
        try {
            Files.createDirectories(storagePath.getParent());
            mapper.writerWithDefaultPrettyPrinter().writeValue(storagePath.toFile(), memory);
        } catch (IOException e) {
            // Never crash on persistence — memory is in-mem, will retry next update
        }
    }

    private @NonNull ProjectMemory emptyMemory() {
        return new ProjectMemory(
            0, workspace, List.of(), List.of(), List.of(), Instant.now()
        );
    }
}
