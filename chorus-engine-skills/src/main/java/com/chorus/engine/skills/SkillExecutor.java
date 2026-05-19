package com.chorus.engine.skills;

import com.chorus.engine.agent.loop.AgentLoop;
import com.chorus.engine.core.event.AgentEvent;
import com.chorus.engine.core.reactive.CancellationToken;
import com.chorus.engine.core.reactive.FlowCollector;
import com.chorus.engine.llm.ToolDefinition;
import com.chorus.engine.tools.Tool;
import com.chorus.engine.tools.ToolRegistry;
import org.jspecify.annotations.NonNull;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeoutException;

/**
 * Executes a skill by building an agent configuration from the skill definition,
 * binding tools by name, and running the agent loop.
 */
public final class SkillExecutor {

    private final String agentId;

    public SkillExecutor() {
        this("skill-agent");
    }

    public SkillExecutor(@NonNull String agentId) {
        this.agentId = Objects.requireNonNull(agentId);
    }

    /**
     * Execute a skill with the given user input.
     *
     * @param skill     the skill definition
     * @param userInput the user's input
     * @param agentLoop the agent loop to use
     * @param tools     the tool registry
     * @return the final answer from the agent
     */
    public @NonNull String execute(
        @NonNull Skill skill,
        @NonNull String userInput,
        @NonNull AgentLoop agentLoop,
        @NonNull ToolRegistry tools
    ) {
        Objects.requireNonNull(skill);
        Objects.requireNonNull(userInput);
        Objects.requireNonNull(agentLoop);
        Objects.requireNonNull(tools);

        // Bind tools by name from the skill definition
        List<ToolDefinition> toolDefs = new ArrayList<>();
        for (String toolName : skill.toolNames()) {
            Tool tool = tools.find(toolName);
            if (tool != null) {
                toolDefs.add(new ToolDefinition(
                    tool.name(),
                    tool.description(),
                    tool.parametersSchema(),
                    null,
                    true
                ));
            }
        }

        CancellationToken token = CancellationToken.create();
        Flow.Publisher<AgentEvent> publisher = agentLoop.run(agentId, userInput, toolDefs, token);

        try {
            List<AgentEvent> events = FlowCollector.toList(publisher, Duration.ofMinutes(5), token);
            return events.stream()
                .filter(e -> e instanceof AgentEvent.Done)
                .map(e -> ((AgentEvent.Done) e).finalAnswer())
                .reduce((a, b) -> b)
                .orElse("ERROR: No final answer produced");
        } catch (TimeoutException e) {
            token.cancel("timeout");
            return "ERROR: Skill execution timed out";
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return "ERROR: Interrupted";
        }
    }
}
