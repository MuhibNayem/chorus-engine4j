package com.chorus.engine.a2a;

import com.chorus.engine.a2a.task.Artifact;
import com.chorus.engine.a2a.task.Message;
import com.chorus.engine.a2a.task.Part;
import com.chorus.engine.a2a.task.Task;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.*;

class TaskLifecycleTest {

    @Test
    void statusEnum_values() {
        assertThat(Task.Status.values()).containsExactly(
            Task.Status.SUBMITTED,
            Task.Status.WORKING,
            Task.Status.INPUT_REQUIRED,
            Task.Status.COMPLETED,
            Task.Status.FAILED,
            Task.Status.CANCELED
        );
    }

    @Test
    void statusJsonValue() {
        assertThat(Task.Status.SUBMITTED.value()).isEqualTo("submitted");
        assertThat(Task.Status.WORKING.value()).isEqualTo("working");
        assertThat(Task.Status.INPUT_REQUIRED.value()).isEqualTo("input-required");
        assertThat(Task.Status.COMPLETED.value()).isEqualTo("completed");
        assertThat(Task.Status.FAILED.value()).isEqualTo("failed");
        assertThat(Task.Status.CANCELED.value()).isEqualTo("canceled");
    }

    @Test
    void messageRole_values() {
        assertThat(Message.Role.values()).containsExactly(Message.Role.USER, Message.Role.AGENT);
    }

    @Test
    void messageRoleJsonValue() {
        assertThat(Message.Role.USER.value()).isEqualTo("user");
        assertThat(Message.Role.AGENT.value()).isEqualTo("agent");
    }

    @Test
    void task_defensivelyCopiesHistory() {
        List<Message> history = new ArrayList<>();
        history.add(new Message(Message.Role.USER, List.of(new Part.TextPart("hi")), null));

        Task task = new Task("id-1", "session-1", Task.Status.SUBMITTED, history, null, null);
        history.add(new Message(Message.Role.AGENT, List.of(new Part.TextPart("bye")), null));

        assertThat(task.history()).hasSize(1);
    }

    @Test
    void task_withArtifactsAndMetadata() {
        List<Artifact> artifacts = List.of(
            new Artifact("result", List.of(new Part.TextPart("output")), null)
        );
        Map<String, Object> metadata = Map.of("key", "value");

        Task task = new Task("id-1", "session-1", Task.Status.COMPLETED,
            List.of(), artifacts, metadata);

        assertThat(task.artifacts()).hasSize(1);
        assertThat(task.metadata()).containsEntry("key", "value");
    }

    @Test
    void task_nullArtifactsAndMetadata() {
        Task task = new Task("id-1", "session-1", Task.Status.SUBMITTED,
            List.of(), null, null);

        assertThat(task.artifacts()).isNull();
        assertThat(task.metadata()).isNull();
    }

    @Test
    void task_defensivelyCopiesArtifacts() {
        List<Artifact> artifacts = new ArrayList<>();
        artifacts.add(new Artifact("a1", List.of(new Part.TextPart("x")), null));
        Task task = new Task("id", "sess", Task.Status.SUBMITTED, List.of(), artifacts, null);
        artifacts.add(new Artifact("a2", List.of(new Part.TextPart("y")), null));
        assertThat(task.artifacts()).hasSize(1);
    }

    @Test
    void task_defensivelyCopiesMetadata() {
        Map<String, Object> meta = new HashMap<>();
        meta.put("a", 1);
        Task task = new Task("id", "sess", Task.Status.SUBMITTED, List.of(), null, meta);
        meta.put("b", 2);
        assertThat(task.metadata()).containsOnlyKeys("a");
    }

    @Test
    void message_defensivelyCopiesParts() {
        List<Part> parts = new ArrayList<>();
        parts.add(new Part.TextPart("hello"));
        Message msg = new Message(Message.Role.USER, parts, null);
        parts.add(new Part.TextPart("world"));
        assertThat(msg.parts()).hasSize(1);
    }

    @Test
    void artifact_defensivelyCopiesParts() {
        List<Part> parts = new ArrayList<>();
        parts.add(new Part.TextPart("data"));
        Artifact artifact = new Artifact("name", parts, null);
        parts.add(new Part.TextPart("more"));
        assertThat(artifact.parts()).hasSize(1);
    }
}
