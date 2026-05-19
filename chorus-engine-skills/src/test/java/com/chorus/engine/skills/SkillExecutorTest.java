package com.chorus.engine.skills;

import com.chorus.engine.agent.loop.AgentLoop;
import com.chorus.engine.core.context.Message;
import com.chorus.engine.core.context.TokenCount;
import com.chorus.engine.llm.ChatResponse;
import com.chorus.engine.llm.LlmClient;
// FakeLlmClient is in the same test package
import com.chorus.engine.tools.Tool;
import com.chorus.engine.tools.ToolError;
import com.chorus.engine.tools.ToolOutput;
import com.chorus.engine.tools.ToolRegistry;
import com.chorus.engine.core.reactive.CancellationToken;
import com.chorus.engine.core.result.Result;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;

import static org.junit.jupiter.api.Assertions.*;

class SkillExecutorTest {

    @Test
    void executeSkillReturnsFinalAnswer() {
        FakeLlmClient fakeLlm = new FakeLlmClient();
        fakeLlm.enqueue(buildResponse("The capital of France is Paris."));

        AgentLoop agentLoop = new AgentLoop(
            "test-agent",
            "You are a helpful assistant.",
            fakeLlm,
            "gpt-4o",
            0.7,
            4096,
            10,
            List.of(),
            null,
            Executors.newVirtualThreadPerTaskExecutor()
        );

        ToolRegistry toolRegistry = new ToolRegistry();

        Skill skill = new Skill(
            "geo-expert",
            "Geography Expert",
            "Answers geography questions",
            "You are a geography expert.",
            List.of(),
            Map.of(),
            List.of("geography")
        );

        SkillExecutor executor = new SkillExecutor();
        String result = executor.execute(skill, "What is the capital of France?", agentLoop, toolRegistry);

        assertEquals("The capital of France is Paris.", result);
    }

    @Test
    void executeSkillBindsTools() {
        FakeLlmClient fakeLlm = new FakeLlmClient();
        fakeLlm.enqueue(buildResponse("Result from tool."));

        AgentLoop agentLoop = new AgentLoop(
            "test-agent",
            "You are a helpful assistant.",
            fakeLlm,
            "gpt-4o",
            0.7,
            4096,
            10,
            List.of(),
            null,
            Executors.newVirtualThreadPerTaskExecutor()
        );

        ToolRegistry toolRegistry = new ToolRegistry();
        toolRegistry.register(new FakeTool("calculator", "Does math", Map.of()));

        Skill skill = new Skill(
            "math-expert",
            "Math Expert",
            "Does math",
            "You are a math expert.",
            List.of("calculator"),
            Map.of(),
            List.of("math")
        );

        SkillExecutor executor = new SkillExecutor();
        String result = executor.execute(skill, "What is 2+2?", agentLoop, toolRegistry);

        assertEquals("Result from tool.", result);
    }

    @Test
    void executeSkillMissingToolIsIgnored() {
        FakeLlmClient fakeLlm = new FakeLlmClient();
        fakeLlm.enqueue(buildResponse("Answer without tool."));

        AgentLoop agentLoop = new AgentLoop(
            "test-agent",
            "You are a helpful assistant.",
            fakeLlm,
            "gpt-4o",
            0.7,
            4096,
            10,
            List.of(),
            null,
            Executors.newVirtualThreadPerTaskExecutor()
        );

        ToolRegistry toolRegistry = new ToolRegistry();

        Skill skill = new Skill(
            "expert",
            "Expert",
            "Expert",
            "You are an expert.",
            List.of("nonexistent_tool"),
            Map.of(),
            List.of()
        );

        SkillExecutor executor = new SkillExecutor();
        String result = executor.execute(skill, "Hello?", agentLoop, toolRegistry);

        assertEquals("Answer without tool.", result);
    }

    private static ChatResponse buildResponse(String content) {
        return new ChatResponse(
            "id-1",
            "gpt-4o",
            "fake",
            Message.assistant(content),
            new TokenCount(10, 5, "fake"),
            Duration.ZERO,
            "stop",
            null,
            null,
            Map.of()
        );
    }

    private record FakeTool(String name, String description, Map<String, Object> parametersSchema) implements Tool {
        @Override
        public Result<ToolOutput, ToolError> execute(Map<String, Object> args, CancellationToken token) {
            return Result.ok(new ToolOutput("done", Map.of()));
        }
    }
}
