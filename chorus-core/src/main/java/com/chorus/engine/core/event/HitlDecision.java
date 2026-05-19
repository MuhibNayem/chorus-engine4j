package com.chorus.engine.core.event;

import java.util.List;
import java.util.Optional;

public sealed interface HitlDecision {
    record Approve() implements HitlDecision {}
    record ApproveSession(List<String> toolNames) implements HitlDecision {}
    record Reject(Optional<String> message) implements HitlDecision {}
}
