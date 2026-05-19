package com.chorus.engine.harness;

import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * Append-only audit log for approval decisions.
 * Stores entries as NDJSON (newline-delimited JSON) for durability and easy streaming.
 */
public final class ApprovalLog {

    private final Path logPath;
    private final ObjectMapper mapper;
    private final String sessionId;

    public ApprovalLog(@NonNull Path logPath, @Nullable String sessionId) {
        this.logPath = logPath;
        this.sessionId = sessionId;
        this.mapper = new ObjectMapper().registerModule(new JavaTimeModule());
    }

    /**
     * Append a new approval decision to the log.
     * Never throws — silently drops on I/O failure to avoid blocking execution.
     */
    public void append(
        @NonNull String tool,
        @NonNull Map<String, Object> args,
        ApprovalLogEntry.ApprovalDecision decision
    ) {
        ApprovalLogEntry entry = new ApprovalLogEntry(
            Instant.now(), tool, args, decision, sessionId
        );
        try {
            Files.createDirectories(logPath.getParent());
            String line = mapper.writeValueAsString(entry) + "\n";
            Files.writeString(logPath, line, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException e) {
            // Never crash on audit logging
        }
    }

    /**
     * Read the last N entries from the log.
     */
    public @NonNull List<ApprovalLogEntry> readLast(int limit) {
        if (!Files.exists(logPath)) {
            return List.of();
        }
        try {
            List<String> lines = Files.readAllLines(logPath);
            List<ApprovalLogEntry> entries = new ArrayList<>();
            int start = Math.max(0, lines.size() - limit);
            for (int i = start; i < lines.size(); i++) {
                String line = lines.get(i).trim();
                if (line.isEmpty()) continue;
                try {
                    entries.add(mapper.readValue(line, new TypeReference<>() {}));
                } catch (IOException ignored) {
                    // Skip corrupted lines
                }
            }
            return Collections.unmodifiableList(entries);
        } catch (IOException e) {
            return List.of();
        }
    }

    /**
     * Read all entries from the log.
     */
    public @NonNull List<ApprovalLogEntry> readAll() {
        return readLast(Integer.MAX_VALUE);
    }

    /**
     * Count total entries in the log.
     */
    public long count() {
        if (!Files.exists(logPath)) return 0;
        try {
            return Files.lines(logPath).filter(l -> !l.isBlank()).count();
        } catch (IOException e) {
            return 0;
        }
    }
}
