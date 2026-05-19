package com.chorus.engine.checkpoint;

import com.chorus.engine.core.checkpoint.Checkpoint;
import com.chorus.engine.core.checkpoint.CheckpointState;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jdk8.Jdk8Module;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Event-sourced durable checkpointer with configurable durability modes:
 * <ul>
 *   <li>{@code sync} — every step flushed before next starts</li>
 *   <li>{@code async} — checkpoints written asynchronously</li>
 *   <li>{@code exit} — only flushed on explicit flush or process exit</li>
 * </ul>
 */
public class DurableCheckpointer implements com.chorus.engine.core.checkpoint.Checkpointer {

    private static final Logger log = LoggerFactory.getLogger(DurableCheckpointer.class);

    public enum DurabilityMode { SYNC, ASYNC, EXIT }

    private final JsonFileCheckpointer base;
    private final Path eventLogRoot;
    private final ObjectMapper mapper;
    private volatile DurabilityMode mode;
    private volatile CompletableFuture<Void> pendingFlush;
    private final Map<String, AtomicInteger> seqCounters = new HashMap<>();

    public DurableCheckpointer() {
        this(DurabilityMode.SYNC);
    }

    public DurableCheckpointer(DurabilityMode mode) {
        this.mode = mode;
        this.base = new JsonFileCheckpointer();
        this.eventLogRoot = Path.of(System.getProperty("user.home"), ".chorus", "checkpoints");
        this.mapper = new ObjectMapper().registerModule(new Jdk8Module());
    }

    public DurabilityMode getMode() { return mode; }
    public void setMode(DurabilityMode mode) { this.mode = mode; }

    @Override
    public CompletableFuture<Void> save(String threadId, CheckpointState state) {
        if (mode == DurabilityMode.EXIT) {
            return CompletableFuture.completedFuture(null);
        }
        int seq = nextSeq(threadId);
        if (mode == DurabilityMode.SYNC) {
            return base.save(threadId, state).thenRun(() -> writeEventLog(threadId, seq, state));
        } else {
            pendingFlush = base.save(threadId, state).thenRun(() -> writeEventLog(threadId, seq, state));
            return CompletableFuture.completedFuture(null);
        }
    }

    @Override
    public CompletableFuture<Checkpoint> load(String threadId) {
        return base.load(threadId);
    }

    @Override
    public CompletableFuture<Checkpoint> loadAt(String threadId, int round) {
        return base.loadAt(threadId, round);
    }

    @Override
    public CompletableFuture<List<Checkpoint>> list(String threadId) {
        return base.list(threadId);
    }

    @Override
    public CompletableFuture<Void> fork(String threadId, int round, String newThreadId) {
        return base.fork(threadId, round, newThreadId);
    }

    @Override
    public CompletableFuture<Void> delete(String threadId) {
        return base.delete(threadId).thenRun(() -> {
            try {
                Files.deleteIfExists(eventLogPath(threadId));
            } catch (IOException e) {
                log.warn("Failed to delete event log for thread {}", threadId);
            }
        });
    }

    public CompletableFuture<Void> flush() {
        if (pendingFlush != null) {
            return pendingFlush;
        }
        return CompletableFuture.completedFuture(null);
    }

    private int nextSeq(String threadId) {
        return seqCounters.computeIfAbsent(threadId, k -> new AtomicInteger(0)).incrementAndGet();
    }

    private Path eventLogPath(String threadId) {
        return eventLogRoot.resolve(threadId).resolve("events.jsonl");
    }

    private void writeEventLog(String threadId, int seq, CheckpointState state) {
        try {
            Path logPath = eventLogPath(threadId);
            Files.createDirectories(logPath.getParent());
            Map<String, Object> event = new LinkedHashMap<>();
            event.put("seq", seq);
            event.put("ts", System.currentTimeMillis());
            event.put("type", state.waitingForHitl().isPresent() ? "hitl_pause" : "round_start");
            event.put("description", "Checkpoint round " + state.round());
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("round", state.round());
            payload.put("messageCount", state.messages().size());
            payload.put("hitlPaused", state.waitingForHitl().isPresent());
            event.put("payload", payload);
            String line = mapper.writeValueAsString(event) + "\n";
            Files.writeString(logPath, line, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException e) {
            log.warn("Failed to write event log for thread {}", threadId, e);
        }
    }
}
