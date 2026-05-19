package com.chorus.engine.harness;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ApprovalLogTest {

    @Test
    void appendAndRead(@TempDir Path temp) {
        Path logPath = temp.resolve("approval.ndjson");
        ApprovalLog log = new ApprovalLog(logPath, "session-1");

        log.append("shell", Map.of("cmd", "ls"), ApprovalLogEntry.ApprovalDecision.APPROVE);
        log.append("file_read", Map.of("path", "/tmp"), ApprovalLogEntry.ApprovalDecision.DENY);
        log.append("web_search", Map.of("q", "java"), ApprovalLogEntry.ApprovalDecision.APPROVE_SESSION);

        assertThat(log.count()).isEqualTo(3);

        var entries = log.readLast(2);
        assertThat(entries).hasSize(2);
        assertThat(entries.get(0).tool()).isEqualTo("file_read");
        assertThat(entries.get(0).decision()).isEqualTo(ApprovalLogEntry.ApprovalDecision.DENY);
        assertThat(entries.get(0).sessionId()).isEqualTo("session-1");
    }

    @Test
    void readAll(@TempDir Path temp) {
        Path logPath = temp.resolve("approval.ndjson");
        ApprovalLog log = new ApprovalLog(logPath, null);

        for (int i = 0; i < 5; i++) {
            log.append("tool" + i, Map.of(), ApprovalLogEntry.ApprovalDecision.APPROVE);
        }

        assertThat(log.readAll()).hasSize(5);
        assertThat(log.readLast(2)).hasSize(2);
    }

    @Test
    void emptyLog(@TempDir Path temp) {
        Path logPath = temp.resolve("missing.ndjson");
        ApprovalLog log = new ApprovalLog(logPath, null);
        assertThat(log.count()).isEqualTo(0);
        assertThat(log.readAll()).isEmpty();
    }
}
