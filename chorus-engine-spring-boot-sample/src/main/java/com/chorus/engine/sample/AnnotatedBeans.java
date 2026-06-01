package com.chorus.engine.sample;

import com.chorus.engine.agent.middleware.Middleware;
import com.chorus.engine.core.context.Message;
import com.chorus.engine.core.event.AgentEvent;
import com.chorus.engine.core.result.Result;
import com.chorus.engine.guardrails.Guardrail;
import com.chorus.engine.guardrails.GuardrailResult;
import com.chorus.engine.telemetry.event.*;
import org.jspecify.annotations.NonNull;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.*;
import java.util.stream.Collectors;

@Component
@com.chorus.engine.annotation.Agent(name = "chorus-code", systemPrompt = """
        You are Chorus Code, an AI coding assistant.
        Read/write files, run shell commands, use git, search the web.
        Be concise, direct, and proactive.""",
        model = "gpt-4o", temperature = 0.7, maxTokens = 4096, maxRounds = 25)
class ChorusCodeAgent {}

@Component
@com.chorus.engine.annotation.SwarmAgent(name = "researcher", instructions = """
        Research specialist. Explore codebases thoroughly. Use filesystem and git tools.""",
        toolNames = {"filesystem", "git", "web_search"})
class ResearcherSwarmAgent {}

@Component
@com.chorus.engine.annotation.SwarmAgent(name = "coder", instructions = """
        Implementation specialist. Write clean, tested code. Follow conventions.""",
        toolNames = {"filesystem", "shell", "git", "web_search"})
class CoderSwarmAgent {}

@Component
@com.chorus.engine.annotation.SwarmAgent(name = "reviewer", instructions = """
        QA specialist. Find bugs and security issues. Run tests, inspect diffs.""",
        handoffTargets = {"coder"}, toolNames = {"filesystem", "git", "shell"})
class ReviewerSwarmAgent {}

@Component
@com.chorus.engine.annotation.SwarmConfig(maxTurns = 15, enableCircuitBreakers = true, orchestrator = "handoff")
class ChorusSwarmConfig {}

@Component
@com.chorus.engine.annotation.EnableSelfHealing(maxRetries = 3)
class ChorusSelfHealingConfig {}

@Component
@com.chorus.engine.annotation.ChorusTool("web-search-enhanced")
class WebSearchToolBean {

    @com.chorus.engine.annotation.Tool(value = "web_search_enhanced", description = "Enhanced web search")
    public String webSearch(
            @com.chorus.engine.annotation.ToolParam(description = "Search query", required = true) String query,
            @com.chorus.engine.annotation.ToolParam(description = "Max results") int numResults) {
        try {
            String encoded = URLEncoder.encode(query, StandardCharsets.UTF_8);
            var client = HttpClient.newHttpClient();
            var request = HttpRequest.newBuilder()
                    .uri(URI.create("https://html.duckduckgo.com/html/?q=" + encoded))
                    .header("User-Agent", "Mozilla/5.0")
                    .timeout(Duration.ofSeconds(10)).GET().build();
            var response = client.send(request, HttpResponse.BodyHandlers.ofString());
            return response.body().lines().limit(numResults)
                    .filter(l -> l.contains("result__")).collect(Collectors.joining("\n"));
        } catch (Exception e) { return "Search error: " + e.getMessage(); }
    }

    @com.chorus.engine.annotation.Tool(value = "file_stats", description = "File statistics")
    public String fileStats(
            @com.chorus.engine.annotation.ToolParam(description = "File path", required = true) String path) {
        try {
            var p = Path.of(path);
            if (!Files.exists(p)) return "File not found: " + path;
            return String.format("File: %s | Size: %d bytes | Readable: %s", p.getFileName(), Files.size(p), Files.isReadable(p));
        } catch (Exception e) { return "Error: " + e.getMessage(); }
    }
}

@Component
@com.chorus.engine.annotation.SkillSource({"classpath:skills/"})
class ChorusSkillsConfig {}

@Component
@com.chorus.engine.annotation.ChunkingStrategy(type = "recursive", size = 512, overlap = 50)
@com.chorus.engine.annotation.VectorStore(type = "in-memory")
class ChorusRagConfig {}

@Component
@com.chorus.engine.annotation.PrimaryLlmProvider("openai")
class PrimaryProviderMarker {}

@Component
class ChorusProviderConfig {
    @org.springframework.context.annotation.Bean
    @com.chorus.engine.annotation.LlmProvider(name = "openai", type = "openai",
            baseUrl = "${OPENAI_BASE_URL:https://api.openai.com/v1}", apiKeyProperty = "OPENAI_API_KEY")
    com.chorus.engine.llm.LlmClient openAiProvider() { return null; }

    @org.springframework.context.annotation.Bean
    @com.chorus.engine.annotation.LlmProvider(name = "anthropic", type = "anthropic", apiKeyProperty = "ANTHROPIC_API_KEY")
    com.chorus.engine.llm.LlmClient anthropicProvider() { return null; }

    @org.springframework.context.annotation.Bean
    @com.chorus.engine.annotation.LlmProvider(name = "gemini", type = "gemini", apiKeyProperty = "GEMINI_API_KEY")
    com.chorus.engine.llm.LlmClient geminiProvider() { return null; }

    @org.springframework.context.annotation.Bean
    @com.chorus.engine.annotation.EmbeddingProvider(name = "openai-embeddings", type = "openai",
            baseUrl = "https://api.openai.com/v1", model = "text-embedding-3-small",
            dimensions = 1536, apiKeyProperty = "OPENAI_API_KEY")
    com.chorus.engine.llm.embed.EmbeddingClient openAiEmbeddingProvider() { return null; }
}

@Component
@com.chorus.engine.annotation.Guardrail(tier = 1, name = "sql-injection")
class SqlInjectionGuardrail implements Guardrail {
    @Override public @NonNull String name() { return "sql-injection"; }
    @Override public int tier() { return 1; }
    @Override public @NonNull GuardrailResult evaluate(@NonNull String input, @NonNull GuardrailContext ctx) {
        String l = input.toLowerCase();
        boolean m = l.contains("drop table") || l.contains("delete from");
        return m ? GuardrailResult.block(name(), tier(), "SQL injection", 0.9, Duration.ZERO)
                : GuardrailResult.allow(name(), tier(), Duration.ZERO);
    }
}

@Component
@com.chorus.engine.annotation.Guardrail(tier = 1, name = "api-key-leak")
class ApiKeyGuardrail implements Guardrail {
    @Override public @NonNull String name() { return "api-key-leak"; }
    @Override public int tier() { return 1; }
    @Override public @NonNull GuardrailResult evaluate(@NonNull String input, @NonNull GuardrailContext ctx) {
        boolean m = java.util.regex.Pattern.compile("(?i)(sk-[a-zA-Z0-9]{20,})").matcher(input).find();
        return m ? GuardrailResult.redact(name(), tier(), "API key found", 0.95, Duration.ZERO)
                : GuardrailResult.allow(name(), tier(), Duration.ZERO);
    }
}

@Component
class ChorusEventHandlers {
    @com.chorus.engine.annotation.EventHandler({"AgentStartEvent"})
    void onAgentStart(AgentStartEvent e) {
        System.out.printf("  [event] Agent start: %s model=%s%n", e.agentId(), e.model());
    }
    @com.chorus.engine.annotation.EventHandler({"AgentEndEvent"})
    void onAgentEnd(AgentEndEvent e) {
        System.out.printf("  [event] Agent end: %dms%n", e.latency().toMillis());
    }
    @com.chorus.engine.annotation.EventHandler
    void onLlmCall(ChorusEvent event) {
        if (event instanceof LlmCallEvent e) {
            System.out.printf("  [event] LLM: %s/%s %d tokens%n", e.provider(), e.model(),
                    e.inputTokens() + e.outputTokens());
        }
    }
}

@Component
@com.chorus.engine.annotation.McpServerCapability(tools = true, resources = true, prompts = true)
class ChorusMcpServerConfig {}

@Component
@com.chorus.engine.annotation.Middleware(priority = 100)
class ContextEnrichmentMiddleware implements Middleware {
    @Override public int priority() { return 100; }
    @Override
    public Result<String, MiddlewareError> extraSystemPrompt(String runId, List<Message> history, Map<String, Object> ctx) {
        return Result.ok("Time: " + java.time.Instant.now() + " | OS: "
                + System.getProperty("os.name") + " | Java: " + Runtime.version());
    }
}
