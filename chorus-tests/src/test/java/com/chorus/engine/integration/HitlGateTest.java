package com.chorus.engine.integration;

import com.chorus.engine.core.ApprovalPolicy;
import com.chorus.engine.core.event.ChatMessage;
import com.chorus.engine.core.event.HitlDecision;
import com.chorus.engine.core.hitl.HitlGate;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.*;

class HitlGateTest {

    @Test
    void testApprove() {
        HitlGate gate = new HitlGate();
        CompletableFuture<HitlDecision> future = gate.waitForDecision("key-1");
        gate.resolve("key-1", new HitlDecision.Approve());
        assertThat(future.join()).isInstanceOf(HitlDecision.Approve.class);
    }

    @Test
    void testReject() {
        HitlGate gate = new HitlGate();
        CompletableFuture<HitlDecision> future = gate.waitForDecision("key-2");
        gate.resolve("key-2", new HitlDecision.Reject(Optional.of("No thanks")));
        HitlDecision decision = future.join();
        assertThat(decision).isInstanceOf(HitlDecision.Reject.class);
        assertThat(((HitlDecision.Reject) decision).message()).hasValue("No thanks");
    }

    @Test
    void testSessionApproval() {
        HitlGate gate = new HitlGate();
        gate.resolve("key-3", new HitlDecision.ApproveSession(List.of("file_write")));

        ChatMessage.ToolCall toolCall = new ChatMessage.ToolCall("1", "file_write", "{}");
        assertThat(gate.shouldPause(List.of(toolCall), ApprovalPolicy.AUTO_EDIT)).isFalse();
    }

    @Test
    void testTimeout() {
        HitlGate gate = new HitlGate(Set.of(), Set.of(), Duration.ofMillis(50));
        CompletableFuture<HitlDecision> future = gate.waitForDecision("key-4");
        assertThatThrownBy(future::join)
            .hasCauseInstanceOf(HitlGate.HitlGateTimeoutException.class);
    }

    @Test
    void testDispose() {
        HitlGate gate = new HitlGate();
        CompletableFuture<HitlDecision> future = gate.waitForDecision("key-5");
        gate.dispose();
        assertThatThrownBy(future::join)
            .hasCauseInstanceOf(HitlGate.HitlGateDisposedException.class);
    }

    @Test
    void testResolveBeforeWait() {
        HitlGate gate = new HitlGate();
        gate.resolve("key-6", new HitlDecision.Approve());
        CompletableFuture<HitlDecision> future = gate.waitForDecision("key-6");
        assertThat(future.join()).isInstanceOf(HitlDecision.Approve.class);
    }
}
