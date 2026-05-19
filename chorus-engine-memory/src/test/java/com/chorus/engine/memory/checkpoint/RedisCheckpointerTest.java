package com.chorus.engine.memory.checkpoint;

import com.chorus.engine.core.checkpoint.AgentState;
import com.chorus.engine.core.checkpoint.Checkpointer.CheckpointError;
import com.chorus.engine.core.checkpoint.Checkpointer.CheckpointRef;
import com.chorus.engine.core.context.Message;
import com.chorus.engine.core.result.Result;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.*;

class RedisCheckpointerTest {

    private RedisCheckpointer checkpointer;
    private FakeJedisPool fakePool;

    @BeforeEach
    void setUp() {
        fakePool = new FakeJedisPool();
        checkpointer = new RedisCheckpointer(fakePool, "test", 3600);
    }

    @Test
    void saveAndLoadLatest() {
        AgentState state = state("run-a", 1L);
        Result<Void, CheckpointError> save = checkpointer.save("run-a", 1L, state);
        assertThat(save.isOk()).isTrue();

        Result<AgentState, CheckpointError> loaded = checkpointer.loadLatest("run-a");
        assertThat(loaded.isOk()).isTrue();
        assertThat(loaded.unwrap().runId()).isEqualTo("run-a");
        assertThat(loaded.unwrap().roundIndex()).isEqualTo(1L);
    }

    @Test
    void loadSpecificSequence() {
        AgentState s1 = state("run-b", 1L);
        AgentState s2 = state("run-b", 2L);
        checkpointer.save("run-b", 1L, s1);
        checkpointer.save("run-b", 2L, s2);

        Result<AgentState, CheckpointError> loaded = checkpointer.load("run-b", 1L);
        assertThat(loaded.isOk()).isTrue();
        assertThat(loaded.unwrap().roundIndex()).isEqualTo(1L);
    }

    @Test
    void loadLatest_returnsMostRecent() {
        checkpointer.save("run-c", 1L, state("run-c", 1L));
        checkpointer.save("run-c", 2L, state("run-c", 2L));
        checkpointer.save("run-c", 3L, state("run-c", 3L));

        Result<AgentState, CheckpointError> loaded = checkpointer.loadLatest("run-c");
        assertThat(loaded.unwrap().roundIndex()).isEqualTo(3L);
    }

    @Test
    void list_returnsNewestFirst() {
        checkpointer.save("run-d", 1L, state("run-d", 1L));
        checkpointer.save("run-d", 2L, state("run-d", 2L));

        Result<List<CheckpointRef>, CheckpointError> list = checkpointer.list("run-d");
        assertThat(list.isOk()).isTrue();
        assertThat(list.unwrap()).hasSize(2);
        assertThat(list.unwrap().get(0).sequenceNumber()).isEqualTo(2L);
        assertThat(list.unwrap().get(1).sequenceNumber()).isEqualTo(1L);
    }

    @Test
    void list_returnsEmptyForUnknownRun() {
        Result<List<CheckpointRef>, CheckpointError> list = checkpointer.list("unknown");
        assertThat(list.isOk()).isTrue();
        assertThat(list.unwrap()).isEmpty();
    }

    @Test
    void prune_deletesOlderCheckpoints() {
        checkpointer.save("run-e", 1L, state("run-e", 1L));
        checkpointer.save("run-e", 2L, state("run-e", 2L));
        checkpointer.save("run-e", 3L, state("run-e", 3L));

        Result<Void, CheckpointError> prune = checkpointer.prune("run-e", 2L);
        assertThat(prune.isOk()).isTrue();

        Result<List<CheckpointRef>, CheckpointError> list = checkpointer.list("run-e");
        assertThat(list.unwrap()).hasSize(2);
        assertThat(list.unwrap().get(0).sequenceNumber()).isEqualTo(3L);
        assertThat(list.unwrap().get(1).sequenceNumber()).isEqualTo(2L);
    }

    @Test
    void loadMissing_returnsNotFound() {
        Result<AgentState, CheckpointError> loaded = checkpointer.loadLatest("nonexistent");
        assertThat(loaded.isErr()).isTrue();
        assertThat(loaded.unwrapErr().code()).isEqualTo("NOT_FOUND");
    }

    @Test
    void loadSpecificMissing_returnsNotFound() {
        checkpointer.save("run-f", 1L, state("run-f", 1L));
        Result<AgentState, CheckpointError> loaded = checkpointer.load("run-f", 99L);
        assertThat(loaded.isErr()).isTrue();
        assertThat(loaded.unwrapErr().code()).isEqualTo("NOT_FOUND");
    }

    @Test
    void overwriteExistingCheckpoint() {
        AgentState s1 = state("run-g", 1L);
        AgentState s2 = new AgentState("run-g", 99L, List.of(), Map.of(), Map.of());
        checkpointer.save("run-g", 1L, s1);
        checkpointer.save("run-g", 1L, s2);

        Result<AgentState, CheckpointError> loaded = checkpointer.load("run-g", 1L);
        assertThat(loaded.unwrap().roundIndex()).isEqualTo(99L);
    }

    @Test
    void list_includesTimestamps() {
        long before = System.currentTimeMillis();
        checkpointer.save("run-h", 1L, state("run-h", 1L));
        long after = System.currentTimeMillis();

        Result<List<CheckpointRef>, CheckpointError> list = checkpointer.list("run-h");
        assertThat(list.unwrap()).hasSize(1);
        CheckpointRef ref = list.unwrap().get(0);
        assertThat(ref.timestamp()).isGreaterThanOrEqualTo(before).isLessThanOrEqualTo(after);
    }

    private AgentState state(String runId, long roundIndex) {
        return new AgentState(
            runId,
            roundIndex,
            List.of(Message.user("Hello")),
            Map.of("k", "v"),
            Map.of()
        );
    }
}
