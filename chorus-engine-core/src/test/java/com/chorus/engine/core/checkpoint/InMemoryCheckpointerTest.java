package com.chorus.engine.core.checkpoint;

import com.chorus.engine.core.context.Message;
import com.chorus.engine.core.result.Result;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.*;

class InMemoryCheckpointerTest {

    private InMemoryCheckpointer checkpointer;

    @BeforeEach
    void setUp() {
        checkpointer = new InMemoryCheckpointer();
    }

    @Test
    void saveAndLoadRoundTrip() {
        AgentState state = state("run-1", 0);
        Result<Void, Checkpointer.CheckpointError> saveResult = checkpointer.save("run-1", 1L, state);
        assertThat(saveResult.isOk()).isTrue();

        Result<AgentState, Checkpointer.CheckpointError> loadResult = checkpointer.load("run-1", 1L);
        assertThat(loadResult.isOk()).isTrue();
        assertThat(loadResult.unwrap()).isEqualTo(state);
    }

    @Test
    void loadLatestReturnsMostRecent() {
        AgentState first = state("run-1", 0);
        AgentState second = state("run-1", 1);
        checkpointer.save("run-1", 1L, first);
        checkpointer.save("run-1", 2L, second);

        Result<AgentState, Checkpointer.CheckpointError> latest = checkpointer.loadLatest("run-1");
        assertThat(latest.isOk()).isTrue();
        assertThat(latest.unwrap().roundIndex()).isEqualTo(1L);
    }

    @Test
    void listReturnsNewestFirst() {
        checkpointer.save("run-1", 1L, state("run-1", 0));
        checkpointer.save("run-1", 2L, state("run-1", 1));
        checkpointer.save("run-1", 3L, state("run-1", 2));

        Result<List<Checkpointer.CheckpointRef>, Checkpointer.CheckpointError> listResult = checkpointer.list("run-1");
        assertThat(listResult.isOk()).isTrue();
        List<Checkpointer.CheckpointRef> refs = listResult.unwrap();
        assertThat(refs).hasSize(3);
        assertThat(refs.get(0).sequenceNumber()).isEqualTo(3L);
        assertThat(refs.get(1).sequenceNumber()).isEqualTo(2L);
        assertThat(refs.get(2).sequenceNumber()).isEqualTo(1L);
    }

    @Test
    void listPaginationViaLimitAndOffset() {
        for (long i = 1; i <= 5; i++) {
            checkpointer.save("run-1", i, state("run-1", (int) i));
        }

        Result<List<Checkpointer.CheckpointRef>, Checkpointer.CheckpointError> listResult = checkpointer.list("run-1");
        assertThat(listResult.isOk()).isTrue();
        List<Checkpointer.CheckpointRef> all = listResult.unwrap();
        assertThat(all).hasSize(5);

        int limit = 2;
        int offset = 1;
        List<Checkpointer.CheckpointRef> page = all.stream()
                .skip(offset)
                .limit(limit)
                .toList();
        assertThat(page).hasSize(2);
        assertThat(page.get(0).sequenceNumber()).isEqualTo(4L);
        assertThat(page.get(1).sequenceNumber()).isEqualTo(3L);
    }

    @Test
    void pruneRemovesOlderCheckpoints() {
        checkpointer.save("run-1", 1L, state("run-1", 0));
        checkpointer.save("run-1", 2L, state("run-1", 1));
        checkpointer.save("run-1", 3L, state("run-1", 2));

        Result<Void, Checkpointer.CheckpointError> pruneResult = checkpointer.prune("run-1", 2L);
        assertThat(pruneResult.isOk()).isTrue();

        assertThat(checkpointer.load("run-1", 1L).isErr()).isTrue();
        assertThat(checkpointer.load("run-1", 2L).isOk()).isTrue();
        assertThat(checkpointer.load("run-1", 3L).isOk()).isTrue();
    }

    @Test
    void clearRemovesAll() {
        checkpointer.save("run-1", 1L, state("run-1", 0));
        checkpointer.save("run-2", 1L, state("run-2", 0));
        checkpointer.clear();

        assertThat(checkpointer.loadLatest("run-1").isErr()).isTrue();
        assertThat(checkpointer.loadLatest("run-2").isErr()).isTrue();
    }

    @Test
    void saveNullRunIdThrows() {
        assertThatThrownBy(() -> checkpointer.save(null, 1L, state("run", 0)))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void saveNullStateThrows() {
        assertThatThrownBy(() -> checkpointer.save("run-1", 1L, null))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void loadNullRunIdThrows() {
        assertThatThrownBy(() -> checkpointer.load(null, 1L))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void loadLatestNullRunIdThrows() {
        assertThatThrownBy(() -> checkpointer.loadLatest(null))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void listNullRunIdThrows() {
        assertThatThrownBy(() -> checkpointer.list(null))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void pruneNullRunIdThrows() {
        assertThatThrownBy(() -> checkpointer.prune(null, 1L))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void loadNonExistentRunReturnsErr() {
        Result<AgentState, Checkpointer.CheckpointError> result = checkpointer.load("missing", 1L);
        assertThat(result.isErr()).isTrue();
        assertThat(result.unwrapErr().code()).isEqualTo("NOT_FOUND");
    }

    @Test
    void loadLatestNonExistentRunReturnsErr() {
        Result<AgentState, Checkpointer.CheckpointError> result = checkpointer.loadLatest("missing");
        assertThat(result.isErr()).isTrue();
        assertThat(result.unwrapErr().code()).isEqualTo("NOT_FOUND");
    }

    @Test
    void listNonExistentRunReturnsEmptyList() {
        Result<List<Checkpointer.CheckpointRef>, Checkpointer.CheckpointError> result = checkpointer.list("missing");
        assertThat(result.isOk()).isTrue();
        assertThat(result.unwrap()).isEmpty();
    }

    @Test
    void saveOverwritesSameSequenceNumber() {
        AgentState first = state("run-1", 0);
        AgentState second = state("run-1", 99);
        checkpointer.save("run-1", 1L, first);
        checkpointer.save("run-1", 1L, second);

        Result<AgentState, Checkpointer.CheckpointError> loaded = checkpointer.load("run-1", 1L);
        assertThat(loaded.unwrap().roundIndex()).isEqualTo(99L);
    }

    @Test
    void concurrentSaveAndLoad() throws InterruptedException {
        int threads = 8;
        int checkpointsPerThread = 100;
        ExecutorService executor = Executors.newFixedThreadPool(threads);
        CountDownLatch latch = new CountDownLatch(threads);

        for (int t = 0; t < threads; t++) {
            final int threadId = t;
            executor.submit(() -> {
                try {
                    for (int i = 0; i < checkpointsPerThread; i++) {
                        String runId = "run-" + threadId;
                        long seq = i;
                        checkpointer.save(runId, seq, state(runId, i));
                        // occasional read
                        if (i % 10 == 0) {
                            checkpointer.loadLatest(runId);
                        }
                    }
                } finally {
                    latch.countDown();
                }
            });
        }

        assertThat(latch.await(10, TimeUnit.SECONDS)).isTrue();
        executor.shutdown();

        for (int t = 0; t < threads; t++) {
            String runId = "run-" + t;
            Result<AgentState, Checkpointer.CheckpointError> latest = checkpointer.loadLatest(runId);
            assertThat(latest.isOk()).isTrue();
            assertThat(latest.unwrap().roundIndex()).isEqualTo(checkpointsPerThread - 1L);
        }
    }

    private static AgentState state(String runId, long roundIndex) {
        return new AgentState(
                runId,
                roundIndex,
                List.of(Message.user("hello")),
                Map.of("key", "value"),
                Map.of()
        );
    }
}
