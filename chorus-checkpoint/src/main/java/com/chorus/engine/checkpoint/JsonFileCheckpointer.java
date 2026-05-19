package com.chorus.engine.checkpoint;

import com.chorus.engine.core.checkpoint.Checkpoint;
import com.chorus.engine.core.checkpoint.CheckpointState;
import com.chorus.engine.core.checkpoint.Checkpointer;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jdk8.Jdk8Module;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.*;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Stream;

/**
 * File-based checkpointer with atomic writes (tmp + rename).
 * Suitable for dev/CLI use.
 */
public class JsonFileCheckpointer implements Checkpointer {

    private static final Logger log = LoggerFactory.getLogger(JsonFileCheckpointer.class);
    private final Path rootDir;
    private final ObjectMapper mapper;

    public JsonFileCheckpointer() {
        this(Path.of(System.getProperty("user.home"), ".chorus", "checkpoints"));
    }

    public JsonFileCheckpointer(Path rootDir) {
        this.rootDir = rootDir;
        this.mapper = new ObjectMapper().registerModule(new Jdk8Module());
        try {
            Files.createDirectories(rootDir);
        } catch (IOException e) {
            throw new RuntimeException("Cannot create checkpoint directory", e);
        }
    }

    @Override
    public CompletableFuture<Void> save(String threadId, CheckpointState state) {
        return CompletableFuture.runAsync(() -> {
            try {
                Path threadDir = rootDir.resolve(threadId);
                Files.createDirectories(threadDir);
                Path target = threadDir.resolve(String.format("%06d.json", state.round()));
                Path tmp = Path.of(target + "." + UUID.randomUUID() + ".tmp");
                Checkpoint cp = new Checkpoint(threadId, state.round(), state.messages(),
                    System.currentTimeMillis(), state.waitingForHitl().map(p ->
                        new CheckpointState.HitlPause(p.resumeKey(), p.requests(), p.toolCalls(), p.assistant())));
                mapper.writeValue(tmp.toFile(), cp);
                Files.move(tmp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (IOException e) {
                throw new RuntimeException("Checkpoint save failed", e);
            }
        });
    }

    @Override
    public CompletableFuture<Checkpoint> load(String threadId) {
        return list(threadId).thenApply(list ->
            list.isEmpty() ? null : list.get(list.size() - 1)
        );
    }

    @Override
    public CompletableFuture<Checkpoint> loadAt(String threadId, int round) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                Path file = rootDir.resolve(threadId).resolve(String.format("%06d.json", round));
                if (!Files.exists(file)) return null;
                return mapper.readValue(file.toFile(), Checkpoint.class);
            } catch (IOException e) {
                return null;
            }
        });
    }

    @Override
    public CompletableFuture<List<Checkpoint>> list(String threadId) {
        return CompletableFuture.supplyAsync(() -> {
            try (Stream<Path> files = Files.list(rootDir.resolve(threadId))) {
                return files
                    .filter(p -> p.toString().endsWith(".json"))
                    .sorted(Comparator.comparing(Path::getFileName))
                    .map(p -> {
                        try {
                            return mapper.readValue(p.toFile(), Checkpoint.class);
                        } catch (IOException e) {
                            log.warn("Failed to read checkpoint {}", p, e);
                            return null;
                        }
                    })
                    .filter(cp -> cp != null)
                    .toList();
            } catch (IOException e) {
                return List.of();
            }
        });
    }

    @Override
    public CompletableFuture<Void> fork(String threadId, int round, String newThreadId) {
        return loadAt(threadId, round).thenCompose(cp -> {
            if (cp == null) return CompletableFuture.completedFuture(null);
            return save(newThreadId, new CheckpointState(cp.messages(), cp.round(),
                cp.waitingForHitl().map(p ->
                    new CheckpointState.HitlPause(p.resumeKey(), p.requests(), p.toolCalls(), p.assistant()))));
        });
    }

    @Override
    public CompletableFuture<Void> delete(String threadId) {
        return CompletableFuture.runAsync(() -> {
            try {
                Path dir = rootDir.resolve(threadId);
                if (Files.exists(dir)) {
                    try (Stream<Path> walk = Files.walk(dir)) {
                        walk.sorted(Comparator.reverseOrder()).forEach(p -> {
                            try { Files.delete(p); } catch (IOException ignored) {}
                        });
                    }
                }
            } catch (IOException e) {
                log.warn("Failed to delete checkpoints for thread {}", threadId, e);
            }
        });
    }
}
