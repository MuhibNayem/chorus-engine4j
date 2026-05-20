package com.chorus.engine.memory;

import com.chorus.engine.core.context.Message;
import com.chorus.engine.tokenizer.Tokenizer;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import static org.assertj.core.api.Assertions.*;

class ContextCompactorTest {

    private static Tokenizer fakeTokenizer(int tokensPerMessage) {
        return new Tokenizer() {
            @Override public java.util.List<Integer> encode(String text) { return List.of(); }
            @Override public String decode(java.util.List<Integer> tokens) { return ""; }
            @Override public int countTokens(String text) { return tokensPerMessage; }
            @Override public int countChatTokens(String role, String content) { return tokensPerMessage; }
            @Override public String name() { return "fake"; }
            @Override public int vocabularySize() { return -1; }
        };
    }

    @Test
    void summarizeReducesMessages() {
        ContextCompactor compactor = new ContextCompactor(10, fakeTokenizer(5));
        List<Message> history = List.of(
            Message.system("You are helpful"),
            Message.user("Hello"),
            Message.assistant("Hi there"),
            Message.user("How are you?"),
            Message.assistant("I am fine")
        );

        ContextCompactor.CompactionResult result = compactor.summarize(history, msgs -> "summary");

        assertThat(result.messages()).hasSize(4); // system + summary + last 2
        assertThat(result.strategy()).contains("summarized");
        assertThat(result.messages().get(0).role()).isEqualTo(com.chorus.engine.core.context.Role.SYSTEM);
        assertThat(result.messages().get(1).content()).contains("summarized");
    }

    @Test
    void summarizeEmptyMessageList() {
        ContextCompactor compactor = new ContextCompactor(10, fakeTokenizer(5));
        List<Message> history = List.of();

        ContextCompactor.CompactionResult result = compactor.summarize(history, msgs -> "summary");

        assertThat(result.messages()).isEmpty();
        assertThat(result.strategy()).contains("too short");
    }

    @Test
    void summarizeShortHistoryReturnsOriginal() {
        ContextCompactor compactor = new ContextCompactor(100, fakeTokenizer(5));
        List<Message> history = List.of(
            Message.user("Hello"),
            Message.assistant("Hi")
        );

        ContextCompactor.CompactionResult result = compactor.summarize(history, msgs -> "summary");

        assertThat(result.messages()).hasSize(2);
        assertThat(result.strategy()).contains("too short to compact");
    }

    @Test
    void selectiveRetentionKeepsRelevantMessages() {
        ContextCompactor compactor = new ContextCompactor(10, fakeTokenizer(5));
        List<Message> history = List.of(
            Message.system("You are helpful"),
            Message.user("Tell me about Java"),
            Message.assistant("Java is a language"),
            Message.user("What about Python?"),
            Message.assistant("Python is dynamic"),
            Message.user("Back to Java"),
            Message.assistant("Java runs on JVM")
        );

        ContextCompactor.CompactionResult result = compactor.selectiveRetention(
            history, "Java",
            (msg, query) -> msg.content().toLowerCase(Locale.ROOT).contains(query.toLowerCase(Locale.ROOT)) ? 1.0 : 0.0
        );

        assertThat(result.messages()).hasSizeGreaterThanOrEqualTo(3); // system + at least 2 scored
        assertThat(result.strategy()).contains("selective retention");
    }

    @Test
    void selectiveRetentionShortHistoryReturnsOriginal() {
        ContextCompactor compactor = new ContextCompactor(100, fakeTokenizer(5));
        List<Message> history = List.of(
            Message.user("Hello"),
            Message.assistant("Hi"),
            Message.user("Bye"),
            Message.assistant("Goodbye")
        );

        ContextCompactor.CompactionResult result = compactor.selectiveRetention(
            history, "test", (msg, query) -> 1.0
        );

        assertThat(result.messages()).hasSize(4);
        assertThat(result.strategy()).contains("too short");
    }

    @Test
    void summarizeNullHistoryThrows() {
        ContextCompactor compactor = new ContextCompactor(100, fakeTokenizer(5));
        assertThatThrownBy(() -> compactor.summarize(null, msgs -> ""))
            .isInstanceOf(NullPointerException.class);
    }

    @Test
    void summarizeNullSummarizerThrows() {
        ContextCompactor compactor = new ContextCompactor(100, fakeTokenizer(5));
        assertThatThrownBy(() -> compactor.summarize(List.of(), null))
            .isInstanceOf(NullPointerException.class);
    }

    @Test
    void selectiveRetentionNullHistoryThrows() {
        ContextCompactor compactor = new ContextCompactor(100, fakeTokenizer(5));
        assertThatThrownBy(() -> compactor.selectiveRetention(null, "q", (m, q) -> 1.0))
            .isInstanceOf(NullPointerException.class);
    }

    @Test
    void selectiveRetentionNullQueryThrows() {
        ContextCompactor compactor = new ContextCompactor(100, fakeTokenizer(5));
        assertThatThrownBy(() -> compactor.selectiveRetention(List.of(), null, (m, q) -> 1.0))
            .isInstanceOf(NullPointerException.class);
    }

    @Test
    void selectiveRetentionNullScorerThrows() {
        ContextCompactor compactor = new ContextCompactor(100, fakeTokenizer(5));
        assertThatThrownBy(() -> compactor.selectiveRetention(List.of(), "q", null))
            .isInstanceOf(NullPointerException.class);
    }
}
