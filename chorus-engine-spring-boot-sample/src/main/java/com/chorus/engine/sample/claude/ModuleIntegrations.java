package com.chorus.engine.sample.claude;

import com.chorus.engine.agent.loop.AgentLoop;
import com.chorus.engine.core.reactive.CancellationToken;
import com.chorus.engine.core.vector.VectorOperations;
import com.chorus.engine.guardrails.Guardrail;
import com.chorus.engine.guardrails.TieredGuardrailEngine;
import com.chorus.engine.guardrails.tier.RegexGuardrail;
import com.chorus.engine.guardrails.tier.KeywordGuardrail;
import com.chorus.engine.guardrails.GuardrailResult;
import com.chorus.engine.llm.LlmClient;
import com.chorus.engine.llm.ToolDefinition;
import com.chorus.engine.mcp.client.McpClient;
import com.chorus.engine.mcp.protocol.McpTool;
import com.chorus.engine.mcp.transport.HttpSseTransport;
import com.chorus.engine.mcp.transport.McpTransport;
import com.chorus.engine.rag.document.Chunk;
import com.chorus.engine.rag.store.InMemoryVectorStore;
import com.chorus.engine.rag.store.VectorStore;
import com.chorus.engine.skills.SkillLoader;
import com.chorus.engine.skills.SkillRegistry;
import com.chorus.engine.skills.SkillRouter;
import com.chorus.engine.swarm.*;
import com.chorus.engine.telemetry.event.ChorusEvent;
import com.chorus.engine.telemetry.event.InMemoryEventBus;
import com.chorus.engine.telemetry.metrics.CostTracker;
import com.chorus.engine.telemetry.metrics.MetricsCollector;
import com.chorus.engine.telemetry.metrics.PricingTable;
import com.chorus.engine.telemetry.otel.ChorusOtlpExporter;
import com.chorus.engine.tools.Tool;
import com.chorus.engine.tools.ToolRegistry;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.jspecify.annotations.Nullable;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

final class ModuleIntegrations implements AutoCloseable {

    private final CliRenderer renderer;
    private final LlmClient llmClient;
    private final ToolRegistry toolRegistry;
    private final ExecutorService executor;
    private final ObjectMapper mapper;

    final InMemoryEventBus eventBus;
    final MetricsCollector metricsCollector;
    final CostTracker costTracker;
    @Nullable ChorusOtlpExporter otlpExporter;

    final InMemoryVectorStore vectorStore;
    @Nullable TieredGuardrailEngine guardrailEngine;
    final SkillRegistry skillRegistry;
    final SkillRouter skillRouter;
    @Nullable McpClient mcpClient;
    @Nullable McpTransport mcpTransport;
    @Nullable SwarmOrchestrator swarmOrchestrator;
    private final Map<String, AgentDefinition> swarmAgents = new LinkedHashMap<>();

    ModuleIntegrations(CliRenderer renderer, LlmClient llmClient, ToolRegistry toolRegistry,
                       ExecutorService executor) {
        this.renderer = renderer;
        this.llmClient = llmClient;
        this.toolRegistry = toolRegistry;
        this.executor = executor;
        this.mapper = new ObjectMapper();
        this.eventBus = new InMemoryEventBus();
        this.metricsCollector = new MetricsCollector(10000);
        this.costTracker = new CostTracker(PricingTable.defaults());
        this.vectorStore = new InMemoryVectorStore(VectorOperations.autoDetect());
        this.skillRegistry = new SkillRegistry();
        this.skillRouter = new SkillRouter();
    }

    void initTelemetry() {
        eventBus.subscribe("*", e -> metricsCollector.recordRun());
        eventBus.subscribe("ToolCallEvent", e -> metricsCollector.recordToolCall());

        String otelEndpoint = System.getenv("CHORUS_OTEL_ENDPOINT");
        if (otelEndpoint != null && !otelEndpoint.isBlank()) {
            var config = new ChorusOtlpExporter.Config(otelEndpoint, Map.of(), 1.0, true, "chorus-code");
            otlpExporter = new ChorusOtlpExporter(eventBus, config);
            renderer.success("OTLP export enabled -> " + otelEndpoint);
        }

        String mcpUrl = System.getenv("CHORUS_MCP_URL");
        if (mcpUrl != null && !mcpUrl.isBlank()) {
            try {
                McpTransport transport = new HttpSseTransport(URI.create(mcpUrl), mapper);
                mcpClient = new McpClient(transport);
                var tools = mcpClient.listTools();
                int count = tools.isOk() ? tools.unwrap().size() : 0;
                renderer.success("MCP connected -> " + mcpUrl + " (" + count + " tools)");
            } catch (Exception e) {
                renderer.warn("MCP connection failed: " + e.getMessage());
            }
        }
    }

    void emitEvent(ChorusEvent event) {
        eventBus.publish(event);
    }

    void initGuardrails() {
        List<Guardrail> guards = new ArrayList<>();
        guards.add(RegexGuardrail.block("sql-injection", "(?i)(drop|delete|insert|update)\\s+.*(table|database)"));
        guards.add(RegexGuardrail.block("api-key", "(?i)(sk-[a-zA-Z0-9]{20,})"));
        guards.add(new KeywordGuardrail("dangerous-cmd",
                Set.of("rm -rf /", "sudo rm", "mkfs", "dd if=/dev/zero", ":(){ :|:& };:"),
                GuardrailResult.Action.BLOCK));
        guardrailEngine = new TieredGuardrailEngine(guards, Executors.newVirtualThreadPerTaskExecutor(),
                Duration.ofMillis(500), Duration.ofSeconds(5));
        renderer.info("Guardrails: " + guards.size() + " rules active");
    }

    boolean isInputSafe(String input) {
        if (guardrailEngine == null) return true;
        var result = guardrailEngine.evaluateInput(input, new Guardrail.GuardrailContext("default", "chorus-cli", "input"));
        return result.allowed();
    }

    void indexCodebase(int maxFiles) {
        renderer.info("Indexing codebase for RAG search...");
        try {
            List<Chunk> allChunks = new ArrayList<>();
            Path root = Path.of("").toAbsolutePath();
            Files.walk(root, 6)
                    .filter(p -> Files.isRegularFile(p))
                    .filter(p -> {
                        String name = p.toString();
                        return name.endsWith(".java") || name.endsWith(".kt")
                                || name.endsWith(".md") || name.endsWith(".yml")
                                || name.endsWith(".py") || name.endsWith(".ts")
                                || name.endsWith(".js") || name.endsWith(".rs");
                    })
                    .filter(p -> !p.toString().contains("/build/")
                            && !p.toString().contains("/.git/")
                            && !p.toString().contains("/node_modules/")
                            && !p.toString().contains("/.gradle/"))
                    .limit(maxFiles)
                    .forEach(file -> {
                        try {
                            String content = Files.readString(file);
                            String docId = root.relativize(file).toString();
                            if (content.length() > 4000) {
                                for (int i = 0; i < content.length(); i += 3000) {
                                    int end = Math.min(i + 3000, content.length());
                                    allChunks.add(new Chunk(docId + "#" + (i / 3000), docId,
                                            content.substring(i, end), i / 3000, 768, null,
                                            Map.of("file", docId)));
                                }
                            } else {
                                allChunks.add(new Chunk(docId, docId, content,
                                        0, 256, null, Map.of("file", docId)));
                            }
                        } catch (IOException ignored) {}
                    });
            if (!allChunks.isEmpty()) {
                vectorStore.upsert(allChunks);
            }
            renderer.success("RAG indexed: " + vectorStore.count() + " docs, " + allChunks.size() + " chunks");
        } catch (IOException e) {
            renderer.warn("RAG indexing failed: " + e.getMessage());
        }
    }

    List<VectorStore.RetrievalResult> ragSearch(String query, int topK) {
        return vectorStore.search(hashEmbed(query), topK, Map.of());
    }

    private static float[] hashEmbed(String text) {
        final int DIM = 1536;
        float[] v = new float[DIM];
        String[] tokens = text.toLowerCase().split("\\s+");
        for (String token : tokens) {
            if (token.isEmpty()) continue;
            int h = (token.hashCode() & Integer.MAX_VALUE) % DIM;
            v[h] += 1.0f;
            // bigram: hash consecutive token pair for locality
            for (char c : token.toCharArray()) {
                int ch = (((token.hashCode() * 31) + c) & Integer.MAX_VALUE) % DIM;
                v[ch] += 0.5f;
            }
        }
        double norm = 0;
        for (float f : v) norm += (double) f * f;
        if (norm > 0) {
            float inv = (float) (1.0 / Math.sqrt(norm));
            for (int i = 0; i < DIM; i++) v[i] *= inv;
        }
        return v;
    }

    void loadSkills(Path skillsDir) {
        if (!Files.isDirectory(skillsDir)) return;
        try {
            var loader = new SkillLoader();
            var skills = loader.loadFromDirectory(skillsDir);
            for (var skill : skills) {
                skillRegistry.register(skill);
            }
            renderer.success("Skills: " + skills.size() + " loaded from " + skillsDir);
        } catch (IOException e) {
            renderer.warn("Skill loading failed: " + e.getMessage());
        }
    }

    void initSwarm() {
        List<Tool> tools = new ArrayList<>(toolRegistry.allTools());
        swarmAgents.put("researcher", new AgentDefinition("researcher",
                "Research specialist that explores codebases and gathers information",
                tools, "inherit", null, Map.of("role", "researcher")));
        swarmAgents.put("coder", new AgentDefinition("coder",
                "Implementation specialist that writes and edits code",
                tools, "inherit", null, Map.of("role", "coder")));
        swarmAgents.put("reviewer", new AgentDefinition("reviewer",
                "Code reviewer that finds bugs, security issues, and improvements",
                tools, "inherit", null, Map.of("role", "reviewer")));

        swarmOrchestrator = new HandoffOrchestrator(swarmAgents, llmClient, toolRegistry,
                SwarmConfig.defaults(), executor);
        renderer.info("Swarm: " + swarmAgents.size() + " agents ready");
    }

    void closeSwarm() { swarmOrchestrator = null; }

    List<McpTool> listMcpTools() {
        if (mcpClient == null) return List.of();
        return mcpClient.listTools().isOk() ? mcpClient.listTools().unwrap() : List.of();
    }

    @Override
    public void close() {
        if (otlpExporter != null) otlpExporter.close();
        if (mcpClient != null) mcpClient.close();
    }
}
