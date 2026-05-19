package com.chorus.engine.checkpoint.postgres;

import com.chorus.engine.core.checkpoint.Checkpoint;
import com.chorus.engine.core.checkpoint.CheckpointState;
import com.chorus.engine.core.event.ChatMessage;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests PostgresCheckpointer using H2 in PostgreSQL compatibility mode.
 * Validates schema initialization, CRUD, batching, and health checks.
 */
class PostgresCheckpointerTest {

    private DataSource dataSource;
    private PostgresCheckpointer checkpointer;

    @BeforeEach
    void setUp() throws SQLException {
        // H2 in PostgreSQL mode for testing without a real Postgres instance
        org.h2.jdbcx.JdbcDataSource ds = new org.h2.jdbcx.JdbcDataSource();
        ds.setURL("jdbc:h2:mem:testdb;MODE=PostgreSQL;DB_CLOSE_DELAY=-1");
        ds.setUser("sa");
        ds.setPassword("");
        this.dataSource = ds;
        this.checkpointer = new PostgresCheckpointer(dataSource);
    }

    @AfterEach
    void tearDown() throws SQLException {
        try (Connection conn = dataSource.getConnection();
             var stmt = conn.createStatement()) {
            stmt.execute("DROP TABLE IF EXISTS chorus_checkpoint");
            stmt.execute("DROP TABLE IF EXISTS chorus_checkpoint_meta");
        }
    }

    @Test
    void saveAndLoad() {
        String threadId = "thread-1";
        List<ChatMessage> messages = List.of(
            ChatMessage.system("You are helpful"),
            ChatMessage.user("Hello")
        );
        CheckpointState state = new CheckpointState(messages, 0, Optional.empty());

        checkpointer.save(threadId, state).join();
        Checkpoint loaded = checkpointer.load(threadId).join();

        assertThat(loaded).isNotNull();
        assertThat(loaded.round()).isEqualTo(0);
        assertThat(loaded.messages()).hasSize(2);
        assertThat(loaded.messages().get(0).content()).isEqualTo("You are helpful");
    }

    @Test
    void loadReturnsLatestRound() {
        String threadId = "thread-2";

        checkpointer.save(threadId, new CheckpointState(
            List.of(ChatMessage.user("first")), 0, Optional.empty())).join();
        checkpointer.save(threadId, new CheckpointState(
            List.of(ChatMessage.user("second")), 1, Optional.empty())).join();
        checkpointer.save(threadId, new CheckpointState(
            List.of(ChatMessage.user("third")), 2, Optional.empty())).join();

        Checkpoint loaded = checkpointer.load(threadId).join();
        assertThat(loaded).isNotNull();
        assertThat(loaded.round()).isEqualTo(2);
        assertThat(loaded.messages().get(0).content()).isEqualTo("third");
    }

    @Test
    void loadAtSpecificRound() {
        String threadId = "thread-3";
        checkpointer.save(threadId, new CheckpointState(
            List.of(ChatMessage.user("r0")), 0, Optional.empty())).join();
        checkpointer.save(threadId, new CheckpointState(
            List.of(ChatMessage.user("r1")), 1, Optional.empty())).join();

        Checkpoint cp = checkpointer.loadAt(threadId, 0).join();
        assertThat(cp).isNotNull();
        assertThat(cp.round()).isEqualTo(0);
        assertThat(cp.messages().get(0).content()).isEqualTo("r0");
    }

    @Test
    void listReturnsOrderedByRound() {
        String threadId = "thread-4";
        checkpointer.save(threadId, new CheckpointState(
            List.of(ChatMessage.user("msg2")), 2, Optional.empty())).join();
        checkpointer.save(threadId, new CheckpointState(
            List.of(ChatMessage.user("msg0")), 0, Optional.empty())).join();
        checkpointer.save(threadId, new CheckpointState(
            List.of(ChatMessage.user("msg1")), 1, Optional.empty())).join();

        List<Checkpoint> list = checkpointer.list(threadId).join();
        assertThat(list).hasSize(3);
        assertThat(list.get(0).round()).isEqualTo(0);
        assertThat(list.get(1).round()).isEqualTo(1);
        assertThat(list.get(2).round()).isEqualTo(2);
    }

    @Test
    void forkCreatesNewThreadWithSameState() {
        String threadId = "thread-5";
        List<ChatMessage> messages = List.of(ChatMessage.user("original"));
        checkpointer.save(threadId, new CheckpointState(messages, 3, Optional.empty())).join();

        checkpointer.fork(threadId, 3, "thread-5-fork").join();
        Checkpoint forked = checkpointer.load("thread-5-fork").join();

        assertThat(forked).isNotNull();
        assertThat(forked.round()).isEqualTo(3);
        assertThat(forked.messages().get(0).content()).isEqualTo("original");
    }

    @Test
    void deleteRemovesAllRounds() {
        String threadId = "thread-6";
        checkpointer.save(threadId, new CheckpointState(
            List.of(ChatMessage.user("a")), 0, Optional.empty())).join();
        checkpointer.save(threadId, new CheckpointState(
            List.of(ChatMessage.user("b")), 1, Optional.empty())).join();

        checkpointer.delete(threadId).join();

        assertThat(checkpointer.load(threadId).join()).isNull();
        assertThat(checkpointer.list(threadId).join()).isEmpty();
    }

    @Test
    void healthCheckReturnsTrue() {
        assertThat(checkpointer.isHealthy()).isTrue();
    }

    @Test
    void batchSave() {
        String threadId = "thread-7";
        List<CheckpointState> states = List.of(
            new CheckpointState(List.of(ChatMessage.user("a")), 0, Optional.empty()),
            new CheckpointState(List.of(ChatMessage.user("b")), 1, Optional.empty()),
            new CheckpointState(List.of(ChatMessage.user("c")), 2, Optional.empty())
        );

        checkpointer.saveBatch(threadId, states).join();
        List<Checkpoint> list = checkpointer.list(threadId).join();

        assertThat(list).hasSize(3);
        assertThat(list.get(2).messages().get(0).content()).isEqualTo("c");
    }

    @Test
    void upsertOverwritesExistingRound() {
        String threadId = "thread-8";
        checkpointer.save(threadId, new CheckpointState(
            List.of(ChatMessage.user("old")), 0, Optional.empty())).join();
        checkpointer.save(threadId, new CheckpointState(
            List.of(ChatMessage.user("new")), 0, Optional.empty())).join();

        Checkpoint loaded = checkpointer.load(threadId).join();
        assertThat(loaded.messages().get(0).content()).isEqualTo("new");
    }
}
