package com.chorus.engine.skills;

import com.chorus.engine.agent.loop.AgentLoop;
import com.chorus.engine.tools.ToolRegistry;
import org.jspecify.annotations.NonNull;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Composes multiple skills into a pipeline.
 * Each skill's output becomes the next skill's input.
 */
public final class SkillComposer {

    private final List<Skill> pipeline = new ArrayList<>();

    public @NonNull SkillComposer then(@NonNull Skill skill) {
        pipeline.add(Objects.requireNonNull(skill));
        return this;
    }

    /**
     * Compose a new skill from the pipeline.
     */
    public @NonNull Skill compose(@NonNull String id, @NonNull String name, @NonNull String description) {
        List<String> allTools = new ArrayList<>();
        List<String> allTags = new ArrayList<>();
        Map<String, Object> mergedConfig = new LinkedHashMap<>();
        StringBuilder combinedPrompt = new StringBuilder();

        for (int i = 0; i < pipeline.size(); i++) {
            Skill s = pipeline.get(i);
            combinedPrompt.append("=== STEP ").append(i + 1).append(": ")
                .append(s.name()).append(" ===\n");
            combinedPrompt.append(s.systemPrompt()).append("\n\n");
            allTools.addAll(s.toolNames());
            allTags.addAll(s.tags());
            mergedConfig.putAll(s.config());
        }

        return new Skill(id, name, description, combinedPrompt.toString(),
            List.copyOf(allTools), Map.copyOf(mergedConfig), List.copyOf(allTags));
    }

    /**
     * Execute the pipeline sequentially.
     */
    public @NonNull String execute(
        @NonNull String initialInput,
        @NonNull SkillExecutor executor,
        @NonNull AgentLoop agentLoop,
        @NonNull ToolRegistry tools
    ) {
        String current = initialInput;
        for (Skill skill : pipeline) {
            current = executor.execute(skill, current, agentLoop, tools);
        }
        return current;
    }

    public int size() {
        return pipeline.size();
    }

    public boolean isEmpty() {
        return pipeline.isEmpty();
    }
}
