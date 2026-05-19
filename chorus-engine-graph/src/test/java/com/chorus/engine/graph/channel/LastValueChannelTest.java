package com.chorus.engine.graph.channel;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.*;

class LastValueChannelTest {

    private final LastValueChannel<String> channel = new LastValueChannel<>();

    @Test
    void merge_bothNonNull_returnsUpdate() {
        assertThat(channel.merge("current", "update")).isEqualTo("update");
    }

    @Test
    void merge_currentNull_returnsUpdate() {
        assertThat(channel.merge(null, "update")).isEqualTo("update");
    }

    @Test
    void merge_updateNull_returnsCurrent() {
        assertThat(channel.merge("current", null)).isEqualTo("current");
    }

    @Test
    void merge_bothNull_throws() {
        assertThatThrownBy(() -> channel.merge(null, null))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("Both current and update are null");
    }

    @Test
    void merge_withIntegerType() {
        LastValueChannel<Integer> intChannel = new LastValueChannel<>();
        assertThat(intChannel.merge(42, 99)).isEqualTo(99);
        assertThat(intChannel.merge(42, null)).isEqualTo(42);
        assertThat(intChannel.merge(null, 7)).isEqualTo(7);
    }
}
