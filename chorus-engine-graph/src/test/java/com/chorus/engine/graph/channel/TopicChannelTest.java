package com.chorus.engine.graph.channel;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.*;

class TopicChannelTest {

    private final TopicChannel<String> channel = new TopicChannel<>();

    @Test
    void merge_appendsUpdates() {
        List<String> current = List.of("a", "b");
        List<String> update = List.of("c", "d");

        List<String> result = channel.merge(current, update);

        assertThat(result).containsExactly("a", "b", "c", "d");
    }

    @Test
    void merge_withEmptyLists() {
        assertThat(channel.merge(List.of(), List.of())).isEmpty();
        assertThat(channel.merge(List.of("a"), List.of())).containsExactly("a");
        assertThat(channel.merge(List.of(), List.of("b"))).containsExactly("b");
    }

    @Test
    void merge_withNullCurrent() {
        List<String> update = List.of("x", "y");
        assertThat(channel.merge(null, update)).containsExactly("x", "y");
    }

    @Test
    void merge_withNullUpdate() {
        List<String> current = List.of("x", "y");
        assertThat(channel.merge(current, null)).containsExactly("x", "y");
    }

    @Test
    void merge_bothNull_returnsEmptyList() {
        assertThat(channel.merge(null, null)).isEmpty();
    }

    @Test
    void merge_doesNotMutateInputs() {
        List<String> current = new java.util.ArrayList<>(List.of("a"));
        List<String> update = new java.util.ArrayList<>(List.of("b"));

        channel.merge(current, update);

        assertThat(current).containsExactly("a");
        assertThat(update).containsExactly("b");
    }

    @Test
    void merge_returnsImmutableList() {
        List<String> result = channel.merge(List.of("a"), List.of("b"));
        assertThatThrownBy(() -> result.add("c"))
            .isInstanceOf(UnsupportedOperationException.class);
    }
}
