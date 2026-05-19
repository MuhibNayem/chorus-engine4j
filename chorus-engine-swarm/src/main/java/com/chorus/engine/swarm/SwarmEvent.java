package com.chorus.engine.swarm;

import com.chorus.engine.core.context.Message;
import com.chorus.engine.core.context.TokenCount;
import com.chorus.engine.tools.ToolOutput;
import org.jspecify.annotations.NonNull;

import java.util.List;

/**
 * Sealed interface for all streaming events emitted by a swarm orchestrator.
 */
public sealed interface SwarmEvent {

    record AgentStart(
        @NonNull String agentName,
        @NonNull List<Message> context
    ) implements SwarmEvent {}

    record AgentResponse(
        @NonNull String agentName,
        @NonNull Message response,
        @NonNull TokenCount tokens
    ) implements SwarmEvent {}

    record HandoffEvent(
        @NonNull Handoff handoff
    ) implements SwarmEvent {}

    record ToolExecution(
        @NonNull String agentName,
        @NonNull String toolName,
        @NonNull ToolOutput output
    ) implements SwarmEvent {}

    record CircuitBreakerOpen(
        @NonNull String agentName
    ) implements SwarmEvent {}

    record SwarmComplete(
        @NonNull String finalAgent,
        @NonNull Message finalResponse
    ) implements SwarmEvent {}

    record SwarmError(
        @NonNull String agentName,
        @NonNull Throwable error
    ) implements SwarmEvent {}
}
