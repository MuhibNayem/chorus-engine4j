package com.chorus.engine.memory.checkpoint;

import com.chorus.engine.core.checkpoint.AgentState;
import com.chorus.engine.core.context.Message;
import com.chorus.engine.core.context.Role;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.*;

class JsonCheckpointSerializerTest {

    @Test
    void roundTrip() {
        JsonCheckpointSerializer serializer = new JsonCheckpointSerializer();
        AgentState state = new AgentState(
            "run-1",
            3L,
            List.of(Message.user("Hello"), Message.assistant("Hi!")),
            Map.of("key", "value"),
            Map.of("model", "gpt-4")
        );

        String json = serializer.serialize(state);
        assertThat(json).contains("run-1", "Hello", "Hi!");

        AgentState restored = serializer.deserialize(json);
        assertThat(restored.runId()).isEqualTo("run-1");
        assertThat(restored.roundIndex()).isEqualTo(3L);
        assertThat(restored.history()).hasSize(2);
        assertThat(restored.history().get(0).role()).isEqualTo(Role.USER);
        assertThat(restored.history().get(0).content()).isEqualTo("Hello");
        assertThat(restored.history().get(1).role()).isEqualTo(Role.ASSISTANT);
        assertThat(restored.history().get(1).content()).isEqualTo("Hi!");
        assertThat(restored.context()).containsEntry("key", "value");
        assertThat(restored.metadata()).containsEntry("model", "gpt-4");
    }

    @Test
    void emptyCollections_roundTrip() {
        JsonCheckpointSerializer serializer = new JsonCheckpointSerializer();
        AgentState state = new AgentState(
            "run-2",
            0L,
            List.of(),
            Map.of(),
            Map.of()
        );

        String json = serializer.serialize(state);
        AgentState restored = serializer.deserialize(json);
        assertThat(restored.history()).isEmpty();
        assertThat(restored.context()).isEmpty();
        assertThat(restored.metadata()).isEmpty();
    }

    @Test
    void nullMetadata_roundTrip() {
        JsonCheckpointSerializer serializer = new JsonCheckpointSerializer();
        AgentState state = new AgentState(
            "run-3",
            1L,
            List.of(Message.system("Be helpful")),
            Map.of(),
            Map.of()
        );

        String json = serializer.serialize(state);
        AgentState restored = serializer.deserialize(json);
        assertThat(restored.history().get(0).metadata()).isNull();
    }
}
