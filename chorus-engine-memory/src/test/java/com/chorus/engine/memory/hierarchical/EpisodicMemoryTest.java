package com.chorus.engine.memory.hierarchical;

import com.chorus.engine.core.context.Message;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.*;

class EpisodicMemoryTest {

    @Test
    void recordEpisodeAndRetrieveRecent() {
        EpisodicMemory memory = new EpisodicMemory(100, 30);
        Message msg = Message.user("Hello");

        String id = memory.record(msg, "greeting", Map.of("user", "alice"), "success");

        assertThat(id).isNotNull();
        List<EpisodicMemory.Episode> recent = memory.queryRecent(5);
        assertThat(recent).hasSize(1);
        assertThat(recent.get(0).eventType()).isEqualTo("greeting");
        assertThat(recent.get(0).outcome()).isEqualTo("success");
    }

    @Test
    void retentionDayEnforcement() {
        EpisodicMemory memory = new EpisodicMemory(100, 0);
        Message msg = Message.user("Old event");

        String id = memory.record(msg, "old_event", Map.of(), null);
        assertThat(memory.size()).isEqualTo(1);

        // Recording a second episode triggers eviction; the first is now older than 0 days
        try {
            Thread.sleep(10);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        memory.record(Message.user("New event"), "new_event", Map.of(), null);

        assertThat(memory.get(id)).isNull();
        assertThat(memory.size()).isLessThanOrEqualTo(1);
    }

    @Test
    void maxEpisodesEnforcement() {
        EpisodicMemory memory = new EpisodicMemory(2, 30);

        memory.record(Message.user("A"), "type-a", Map.of(), null);
        memory.record(Message.user("B"), "type-b", Map.of(), null);
        memory.record(Message.user("C"), "type-c", Map.of(), null);

        assertThat(memory.size()).isEqualTo(2);
        List<EpisodicMemory.Episode> recent = memory.queryRecent(5);
        assertThat(recent).extracting(e -> e.message().content()).containsExactly("C", "B");
    }

    @Test
    void queryByEntity() {
        EpisodicMemory memory = new EpisodicMemory(100, 30);
        memory.record(Message.user("Alice says hi"), "chat", Map.of("user", "alice"), null);
        memory.record(Message.user("Bob says hi"), "chat", Map.of("user", "bob"), null);

        List<EpisodicMemory.Episode> results = memory.queryByEntity("user", "alice", 5);
        assertThat(results).hasSize(1);
        assertThat(results.get(0).message().content()).isEqualTo("Alice says hi");
    }

    @Test
    void queryByType() {
        EpisodicMemory memory = new EpisodicMemory(100, 30);
        memory.record(Message.user("A"), "error", Map.of(), null);
        memory.record(Message.user("B"), "info", Map.of(), null);
        memory.record(Message.user("C"), "error", Map.of(), null);

        List<EpisodicMemory.Episode> results = memory.queryByType("error", 5);
        assertThat(results).hasSize(2);
    }

    @Test
    void queryByTimeRange() {
        EpisodicMemory memory = new EpisodicMemory(100, 30);
        Instant now = Instant.now();
        String id = memory.record(Message.user("Now"), "test", Map.of(), null);

        List<EpisodicMemory.Episode> results = memory.queryByTimeRange(now.minus(1, ChronoUnit.HOURS), now.plus(1, ChronoUnit.HOURS));
        assertThat(results).hasSize(1);

        List<EpisodicMemory.Episode> empty = memory.queryByTimeRange(now.plus(1, ChronoUnit.HOURS), now.plus(2, ChronoUnit.HOURS));
        assertThat(empty).isEmpty();
    }

    @Test
    void incrementAccessCount() {
        EpisodicMemory memory = new EpisodicMemory(100, 30);
        String id = memory.record(Message.user("Hot"), "test", Map.of(), null);

        memory.incrementAccessCount(id);
        memory.incrementAccessCount(id);

        EpisodicMemory.Episode ep = memory.get(id);
        assertThat(ep).isNotNull();
        assertThat(ep.accessCount()).isEqualTo(3);
    }

    @Test
    void findHotEpisodes() {
        EpisodicMemory memory = new EpisodicMemory(100, 30);
        String id1 = memory.record(Message.user("A"), "test", Map.of(), null);
        String id2 = memory.record(Message.user("B"), "test", Map.of(), null);

        memory.incrementAccessCount(id1);
        memory.incrementAccessCount(id1);

        List<EpisodicMemory.Episode> hot = memory.findHotEpisodes(2);
        assertThat(hot).hasSize(1);
        assertThat(hot.get(0).id()).isEqualTo(id1);
    }

    @Test
    void nullMessageRejection() {
        EpisodicMemory memory = new EpisodicMemory(100, 30);
        assertThatThrownBy(() -> memory.record(null, "type", Map.of(), null))
            .isInstanceOf(NullPointerException.class);
    }

    @Test
    void nullEventTypeRejection() {
        EpisodicMemory memory = new EpisodicMemory(100, 30);
        assertThatThrownBy(() -> memory.record(Message.user("test"), null, Map.of(), null))
            .isInstanceOf(NullPointerException.class);
    }
}
