package com.chorus.engine.swarm;

import com.chorus.engine.tools.Tool;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Blueprint for an agent in the swarm.
 *
 * @param name            Unique agent identifier
 * @param instructions    System prompt / role description
 * @param tools           Tools available to this agent
 * @param model           LLM model identifier
 * @param handoffTargets  Agents this one can transfer control to
 * @param metadata        Arbitrary metadata for routing and observability
 */
public record AgentDefinition(
    @NonNull String name,
    @NonNull String instructions,
    @NonNull List<Tool> tools,
    @NonNull String model,
    @Nullable List<String> handoffTargets,
    @NonNull Map<String, Object> metadata
) {

    public AgentDefinition {
        Objects.requireNonNull(name, "name cannot be null");
        Objects.requireNonNull(instructions, "instructions cannot be null");
        Objects.requireNonNull(tools, "tools cannot be null");
        tools = List.copyOf(tools);
        Objects.requireNonNull(model, "model cannot be null");
        handoffTargets = handoffTargets != null ? List.copyOf(handoffTargets) : null;
        Objects.requireNonNull(metadata, "metadata cannot be null");
        metadata = Map.copyOf(metadata);
    }

    public @NonNull AgentDefinition withHandoffTargets(@Nullable List<String> targets) {
        return new AgentDefinition(name, instructions, tools, model, targets, metadata);
    }
}
