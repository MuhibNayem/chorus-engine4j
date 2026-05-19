package com.chorus.engine.core.checkpoint;

import com.chorus.engine.core.event.ChatMessage;
import com.chorus.engine.core.event.HitlRequest;

import java.util.List;
import java.util.Optional;

public record CheckpointState(
    List<ChatMessage> messages,
    int round,
    Optional<HitlPause> waitingForHitl
) {

    public record HitlPause(
        String resumeKey,
        List<HitlRequest> requests,
        List<ChatMessage.ToolCall> toolCalls,
        ChatMessage assistant
    ) {}

    public CheckpointState withMessages(List<ChatMessage> messages) {
        return new CheckpointState(messages, round, waitingForHitl);
    }

    public CheckpointState withRound(int round) {
        return new CheckpointState(messages, round, waitingForHitl);
    }

    public CheckpointState withWaitingForHitl(Optional<HitlPause> waitingForHitl) {
        return new CheckpointState(messages, round, waitingForHitl);
    }
}
