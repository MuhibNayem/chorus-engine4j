package com.chorus.engine.core.event;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.*;

class AgentEventTest {

    private static final Instant NOW = Instant.now();

    @Test
    void allRecordTypesCanBeConstructed() {
        AgentEvent streamToken = new AgentEvent.StreamToken("run", NOW, "tok", 1L, null);
        assertThat(streamToken.runId()).isEqualTo("run");

        AgentEvent thinkingStart = new AgentEvent.ThinkingStart("run", NOW, 1L);
        assertThat(thinkingStart).isNotNull();

        AgentEvent thinkingEnd = new AgentEvent.ThinkingEnd("run", NOW, 1L);
        assertThat(thinkingEnd).isNotNull();

        AgentEvent toolCallStart = new AgentEvent.ToolCallStart("run", NOW, "tool", Map.of("k", "v"), 1L);
        assertThat(toolCallStart).isNotNull();

        AgentEvent toolCallDone = new AgentEvent.ToolCallDone("run", NOW, "tool", "result", 100L, 1L);
        assertThat(toolCallDone).isNotNull();

        AgentEvent toolCallError = new AgentEvent.ToolCallError("run", NOW, "tool", "fail", true, 1L);
        assertThat(toolCallError).isNotNull();

        AgentEvent roundStart = new AgentEvent.RoundStart("run", NOW, 1L, 10, 100);
        assertThat(roundStart).isNotNull();

        AgentEvent roundEnd = new AgentEvent.RoundEnd("run", NOW, 1L, 5, "stop");
        assertThat(roundEnd).isNotNull();

        AgentEvent hitlRequested = new AgentEvent.HitlRequested("run", NOW, "gate", "tool", Map.of(), 5000L);
        assertThat(hitlRequested).isNotNull();

        AgentEvent hitlResolved = new AgentEvent.HitlResolved("run", NOW, "gate", AgentEvent.HitlDecision.APPROVE, "ok");
        assertThat(hitlResolved).isNotNull();

        AgentEvent checkpointSaved = new AgentEvent.CheckpointSaved("run", NOW, "key", 1L);
        assertThat(checkpointSaved).isNotNull();

        AgentEvent checkpointLoaded = new AgentEvent.CheckpointLoaded("run", NOW, "key", 1L);
        assertThat(checkpointLoaded).isNotNull();

        AgentEvent compactionTriggered = new AgentEvent.CompactionTriggered("run", NOW, 1L, 100, 50, "summarize");
        assertThat(compactionTriggered).isNotNull();

        AgentEvent guardrailTriggered = new AgentEvent.GuardrailTriggered(
                "run", NOW, "gr", 1, "keyword", "bad", AgentEvent.GuardrailAction.BLOCK
        );
        assertThat(guardrailTriggered).isNotNull();

        AgentEvent memoryRecall = new AgentEvent.MemoryRecall("run", NOW, Set.of("m1"), 10);
        assertThat(memoryRecall).isNotNull();

        AgentEvent memoryStore = new AgentEvent.MemoryStore("run", NOW, "m1", 5);
        assertThat(memoryStore).isNotNull();

        AgentEvent handoff = new AgentEvent.Handoff("run", NOW, "a1", "a2", Map.of());
        assertThat(handoff).isNotNull();

        AgentEvent streamStart = new AgentEvent.StreamStart("run", NOW, "model", "openai");
        assertThat(streamStart).isNotNull();

        AgentEvent streamEnd = new AgentEvent.StreamEnd("run", NOW, "stop", 10, 5);
        assertThat(streamEnd).isNotNull();

        AgentEvent done = new AgentEvent.Done("run", NOW, "answer", 3, 10, 5, 1000L);
        assertThat(done).isNotNull();

        AgentEvent error = new AgentEvent.Error("run", NOW, "type", "msg", null, false);
        assertThat(error).isNotNull();
    }

    @Test
    void nullRejectionOnKeyFields() {
        // These records do not enforce @NonNull at runtime; verify they accept nulls gracefully
        assertThatNoException().isThrownBy(() -> new AgentEvent.StreamToken(null, NOW, "tok", 1L, null));

        assertThatNoException().isThrownBy(() -> new AgentEvent.ToolCallStart("run", NOW, null, Map.of(), 1L));

        assertThatNoException().isThrownBy(() -> new AgentEvent.GuardrailTriggered(
                "run", NOW, null, 1, "type", "bad", AgentEvent.GuardrailAction.WARN
        ));

        assertThatNoException().isThrownBy(() -> new AgentEvent.Done("run", NOW, null, 1, 1, 1, 1L));

        assertThatNoException().isThrownBy(() -> new AgentEvent.Error("run", NOW, "type", null, null, false));

        assertThatNoException().isThrownBy(() -> new AgentEvent.HitlResolved("run", NOW, "gate", null, "reason"));
    }

    @Test
    void hitlDecisionEnumValues() {
        assertThat(AgentEvent.HitlDecision.values())
                .containsExactly(AgentEvent.HitlDecision.APPROVE, AgentEvent.HitlDecision.APPROVE_SESSION, AgentEvent.HitlDecision.REJECT);
    }

    @Test
    void guardrailActionEnumValues() {
        assertThat(AgentEvent.GuardrailAction.values())
                .containsExactly(AgentEvent.GuardrailAction.BLOCK, AgentEvent.GuardrailAction.WARN, AgentEvent.GuardrailAction.REDACT, AgentEvent.GuardrailAction.LOG);
    }
}
