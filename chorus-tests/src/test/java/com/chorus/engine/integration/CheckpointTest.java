package com.chorus.engine.integration;

import com.chorus.engine.checkpoint.JsonFileCheckpointer;
import com.chorus.engine.core.checkpoint.Checkpoint;
import com.chorus.engine.core.checkpoint.CheckpointState;
import com.chorus.engine.core.event.ChatMessage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.*;

class CheckpointTest {

    private Path tempDir;
    private JsonFileCheckpointer checkpointer;

    @BeforeEach
    void setUp() throws Exception {
        tempDir = Files.createTempDirectory("chorus-test");
        checkpointer = new JsonFileCheckpointer(tempDir);
    }

    @Test
    void testSaveAndLoad() {
        String threadId = "thread-1";
        CheckpointState state = new CheckpointState(
            List.of(ChatMessage.user("hello")), 0, Optional.empty()
        );
        checkpointer.save(threadId, state).join();
        Checkpoint loaded = checkpointer.load(threadId).join();

        assertThat(loaded).isNotNull();
        assertThat(loaded.threadId()).isEqualTo(threadId);
        assertThat(loaded.round()).isEqualTo(0);
        assertThat(loaded.messages()).hasSize(1);
    }

    @Test
    void testLoadAtSpecificRound() {
        String threadId = "thread-2";
        checkpointer.save(threadId, new CheckpointState(List.of(), 0, Optional.empty())).join();
        checkpointer.save(threadId, new CheckpointState(List.of(), 1, Optional.empty())).join();
        checkpointer.save(threadId, new CheckpointState(List.of(), 2, Optional.empty())).join();

        Checkpoint loaded = checkpointer.loadAt(threadId, 1).join();
        assertThat(loaded.round()).isEqualTo(1);
    }

    @Test
    void testFork() {
        String threadId = "thread-3";
        String newThreadId = "thread-3-fork";
        checkpointer.save(threadId, new CheckpointState(List.of(ChatMessage.user("hi")), 5, Optional.empty())).join();
        checkpointer.fork(threadId, 5, newThreadId).join();

        Checkpoint forked = checkpointer.load(newThreadId).join();
        assertThat(forked.round()).isEqualTo(5);
        assertThat(forked.messages()).hasSize(1);
    }

    @Test
    void testDelete() {
        String threadId = "thread-4";
        checkpointer.save(threadId, new CheckpointState(List.of(), 0, Optional.empty())).join();
        checkpointer.delete(threadId).join();
        List<Checkpoint> list = checkpointer.list(threadId).join();
        assertThat(list).isEmpty();
    }
}
