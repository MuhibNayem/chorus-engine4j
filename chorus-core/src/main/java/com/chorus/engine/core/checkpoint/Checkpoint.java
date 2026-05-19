package com.chorus.engine.core.checkpoint;

import com.chorus.engine.core.event.ChatMessage;
import com.chorus.engine.core.event.HitlRequest;

import java.util.List;
import java.util.Optional;

public record Checkpoint(
    String threadId,
    int round,
    List<ChatMessage> messages,
    long createdAt,
    Optional<CheckpointState.HitlPause> waitingForHitl
) {}
