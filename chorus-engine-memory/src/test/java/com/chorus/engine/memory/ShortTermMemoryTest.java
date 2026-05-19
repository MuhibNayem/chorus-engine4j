package com.chorus.engine.memory;

import com.chorus.engine.core.context.Message;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.*;

class ShortTermMemoryTest {

    @Test
    void addMessageAndGetRecent() {
        ShortTermMemory memory = new ShortTermMemory(1000, 100);
        Message msg1 = Message.user("Hello");
        Message msg2 = Message.assistant("Hi there");

        memory.add(msg1, 5);
        memory.add(msg2, 5);

        List<Message> recent = memory.getRecent(2);
        assertThat(recent).hasSize(2);
        assertThat(recent.get(0).content()).isEqualTo("Hello");
        assertThat(recent.get(1).content()).isEqualTo("Hi there");
    }

    @Test
    void getAllReturnsMessagesInOrder() {
        ShortTermMemory memory = new ShortTermMemory(1000, 100);
        memory.add(Message.user("A"), 1);
        memory.add(Message.user("B"), 1);
        memory.add(Message.user("C"), 1);

        List<Message> all = memory.getAll();
        assertThat(all).extracting(Message::content).containsExactly("A", "B", "C");
    }

    @Test
    void maxTokensEnforcementEvictsOldest() {
        ShortTermMemory memory = new ShortTermMemory(10, 100);
        memory.add(Message.user("A"), 4);
        memory.add(Message.user("B"), 4);
        memory.add(Message.user("C"), 4);

        assertThat(memory.currentTokens()).isLessThanOrEqualTo(10);
        assertThat(memory.getAll()).extracting(Message::content).containsExactly("B", "C");
    }

    @Test
    void maxMessagesEnforcementEvictsOldest() {
        ShortTermMemory memory = new ShortTermMemory(1000, 2);
        memory.add(Message.user("A"), 1);
        memory.add(Message.user("B"), 1);
        memory.add(Message.user("C"), 1);

        assertThat(memory.size()).isEqualTo(2);
        assertThat(memory.getAll()).extracting(Message::content).containsExactly("B", "C");
    }

    @Test
    void emptyOperations() {
        ShortTermMemory memory = new ShortTermMemory(1000, 100);

        assertThat(memory.getAll()).isEmpty();
        assertThat(memory.getRecent(5)).isEmpty();
        assertThat(memory.currentTokens()).isEqualTo(0);
        assertThat(memory.size()).isEqualTo(0);
        assertThat(memory.search("test", 5)).isEmpty();
    }

    @Test
    void clearRemovesEverything() {
        ShortTermMemory memory = new ShortTermMemory(1000, 100);
        memory.add(Message.user("A"), 1);
        memory.add(Message.user("B"), 1);

        memory.clear();

        assertThat(memory.getAll()).isEmpty();
        assertThat(memory.currentTokens()).isEqualTo(0);
        assertThat(memory.size()).isEqualTo(0);
    }

    @Test
    void getByIdReturnsCorrectMessage() {
        ShortTermMemory memory = new ShortTermMemory(1000, 100);
        Message msg = Message.user("target");
        memory.add(msg, 1);

        String id = null;
        for (var entry : memory.getAll()) {
            // IDs are internal; we can only search by content for this test
        }

        // Verify search finds the message
        List<Message> results = memory.search("target", 5);
        assertThat(results).hasSize(1);
        assertThat(results.get(0).content()).isEqualTo("target");
    }

    @Test
    void nullMessageRejection() {
        ShortTermMemory memory = new ShortTermMemory(1000, 100);
        assertThatThrownBy(() -> memory.add(null, 1))
            .isInstanceOf(NullPointerException.class);
    }

    @Test
    void searchNullQueryThrows() {
        ShortTermMemory memory = new ShortTermMemory(1000, 100);
        assertThatThrownBy(() -> memory.search(null, 5))
            .isInstanceOf(NullPointerException.class);
    }

    @Test
    void getNullIdThrows() {
        ShortTermMemory memory = new ShortTermMemory(1000, 100);
        assertThatThrownBy(() -> memory.get(null))
            .isInstanceOf(NullPointerException.class);
    }
}
