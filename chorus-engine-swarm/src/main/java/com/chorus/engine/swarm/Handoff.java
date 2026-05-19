package com.chorus.engine.swarm;

import com.chorus.engine.core.context.Message;
import org.jspecify.annotations.NonNull;

import java.util.List;
import java.util.Objects;

/**
 * Transfer control from one agent to another.
 *
 * @param fromAgent  Agent yielding control
 * @param toAgent    Agent receiving control
 * @param reason     Human-readable rationale
 * @param messages   Context passed to the new agent
 */
public record Handoff(
    @NonNull String fromAgent,
    @NonNull String toAgent,
    @NonNull String reason,
    @NonNull List<Message> messages
) {

    public Handoff {
        Objects.requireNonNull(fromAgent, "fromAgent cannot be null");
        Objects.requireNonNull(toAgent, "toAgent cannot be null");
        Objects.requireNonNull(reason, "reason cannot be null");
        Objects.requireNonNull(messages, "messages cannot be null");
        messages = List.copyOf(messages);
    }
}
