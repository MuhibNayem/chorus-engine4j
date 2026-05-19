package com.chorus.engine.core.context;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.*;

class MessageTest {

    @Test
    void factory_methods() {
        Message sys = Message.system("You are helpful");
        assertThat(sys.role()).isEqualTo(Role.SYSTEM);
        assertThat(sys.content()).isEqualTo("You are helpful");

        Message user = Message.user("Hello");
        assertThat(user.role()).isEqualTo(Role.USER);
        assertThat(user.name()).isNull();

        Message named = Message.user("Hello", "Alice");
        assertThat(named.name()).isEqualTo("Alice");

        Message assistant = Message.assistant("Hi there");
        assertThat(assistant.role()).isEqualTo(Role.ASSISTANT);

        Message tool = Message.tool("result", "call_123");
        assertThat(tool.role()).isEqualTo(Role.TOOL);
        assertThat(tool.toolCallId()).isEqualTo("call_123");
    }

    @Test
    void withContent_creates_copy() {
        Message m = Message.user("old");
        Message updated = m.withContent("new");
        assertThat(updated.content()).isEqualTo("new");
        assertThat(m.content()).isEqualTo("old");
    }

    @Test
    void null_role_rejected() {
        assertThatThrownBy(() -> new Message(null, "test", null, null, null))
            .isInstanceOf(NullPointerException.class);
    }

    @Test
    void null_content_rejected() {
        assertThatThrownBy(() -> new Message(Role.USER, null, null, null, null))
            .isInstanceOf(NullPointerException.class);
    }

    @Test
    void role_fromString() {
        assertThat(Role.fromString("system")).isEqualTo(Role.SYSTEM);
        assertThat(Role.fromString("USER")).isEqualTo(Role.USER);
        assertThat(Role.fromString("assistant")).isEqualTo(Role.ASSISTANT);
        assertThat(Role.fromString("human")).isEqualTo(Role.USER);
        assertThat(Role.fromString("ai")).isEqualTo(Role.ASSISTANT);
        assertThat(Role.fromString("tool")).isEqualTo(Role.TOOL);
        assertThat(Role.fromString("function")).isEqualTo(Role.TOOL);
    }

    @Test
    void role_fromString_unknown() {
        assertThatThrownBy(() -> Role.fromString("unknown"))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void withMetadata_createsCopy() {
        Message original = Message.user("hello");
        Message updated = original.withMetadata(Map.of("key", "value"));
        assertThat(updated.metadata()).containsEntry("key", "value");
        assertThat(original.metadata()).isNull();
    }

    @Test
    void metadataImmutability() {
        java.util.HashMap<String, Object> mutable = new java.util.HashMap<>();
        mutable.put("a", 1);
        Message m = new Message(Role.USER, "hi", null, null, mutable);
        mutable.put("b", 2);
        assertThat(m.metadata()).containsOnlyKeys("a");
        assertThatThrownBy(() -> m.metadata().put("c", 3))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void toolFactory_assertions() {
        Message tool = Message.tool("tool result", "call_123");
        assertThat(tool.role()).isEqualTo(Role.TOOL);
        assertThat(tool.content()).isEqualTo("tool result");
        assertThat(tool.toolCallId()).isEqualTo("call_123");
        assertThat(tool.name()).isNull();
        assertThat(tool.metadata()).isNull();
    }
}
