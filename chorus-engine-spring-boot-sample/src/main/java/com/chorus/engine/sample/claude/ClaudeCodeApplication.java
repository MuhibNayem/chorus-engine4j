package com.chorus.engine.sample.claude;

import com.chorus.engine.agent.loop.AgentLoop;
import com.chorus.engine.agent.middleware.Middleware;
import com.chorus.engine.core.checkpoint.AgentState;
import com.chorus.engine.core.context.Message;
import com.chorus.engine.core.event.AgentEvent;
import com.chorus.engine.core.reactive.CancellationToken;
import com.chorus.engine.core.result.Result;
import com.chorus.engine.llm.LlmClient;
import com.chorus.engine.llm.ToolDefinition;
import com.chorus.engine.llm.provider.MockLlmClient;
import com.chorus.engine.llm.provider.ProviderRegistry;
import com.chorus.engine.llm.retry.CircuitBreaker;
import com.chorus.engine.llm.retry.RetryPolicy;
import com.chorus.engine.tools.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

import java.io.*;
import java.net.http.HttpClient;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

public final class ClaudeCodeApplication {

    private static final String VERSION = "0.2.0";

    public static void main(String[] args) throws Exception {
        CliArgs cliArgs = CliArgs.parse(args);
        if (cliArgs.help()) {
            showHelpAndExit();
            return;
        }

        var app = new ClaudeCodeApplication();
        app.run(cliArgs);
    }

    private final CliRenderer renderer = new CliRenderer();
    private final CliSession session = new CliSession();
    private final SkillCommands skills = new SkillCommands(renderer, session);
    private final SessionStore sessionStore = new SessionStore();
    private final AtomicBoolean running = new AtomicBoolean(true);

    // Tier 1 features
    private final CheckpointManager checkpointManager = new CheckpointManager();
    private final PermissionController permissionController = new PermissionController();
    private final EffortController effortController = new EffortController();
    private final GoalTracker goalTracker = new GoalTracker();
    private final MemoryManager memoryManager = new MemoryManager(8000, 40);
    private SkillAutoLoader skillAutoLoader;
    private CompactionMiddleware compactionMiddleware;
    private ModuleIntegrations integrations;

    private LlmClient llmClient;
    private AgentLoop agentLoop;
    private ToolRegistry toolRegistry;
    private ExecutorService executor;
    private String model;
    private Path workspace;
    private boolean planMode;
    private CliArgs cliArgs;

    void run(CliArgs cliArgs) throws Exception {
        this.cliArgs = cliArgs;
        configureWorkspace();
        configureLlm();
        configureTools();
        loadSkills();
        loadProjectContext();
        configureMemory();
        configureEffort(cliArgs);

        integrations = new ModuleIntegrations(renderer, llmClient, toolRegistry, executor);
        integrations.initTelemetry();
        integrations.initGuardrails();
        integrations.initSwarm();

        if (cliArgs.nonInteractive()) {
            runNonInteractive(cliArgs.prompt());
        } else {
            runInteractive();
        }
    }

    // ---- Non-interactive (pipe) mode ----

    void runNonInteractive(String prompt) {
        if (prompt == null || prompt.isBlank()) {
            try {
                if (System.in.available() > 0) {
                    prompt = new String(System.in.readAllBytes()).trim();
                }
            } catch (IOException e) {
                prompt = "help";
            }
            if (prompt == null || prompt.isBlank()) {
                prompt = "help";
            }
        }

        configureAgent();
        List<ToolDefinition> toolDefs = ToolWiring.toToolDefinitions(toolRegistry);
        CancellationToken token = CancellationToken.create();
        var events = agentLoop.run(session.getRunId(), prompt, toolDefs, token);

        if ("json".equals(cliArgs.outputFormat())) {
            outputJsonStream(events, token);
        } else if ("stream-json".equals(cliArgs.outputFormat())) {
            outputStreamingJson(events, token);
        } else {
            outputText(events, token);
        }

        executor.shutdown();
    }

    record JsonOutput(String type, String content, String toolCall, Map<String, Object> args, Integer round, Long timestamp) {}

    void outputText(Flow.Publisher<AgentEvent> events, CancellationToken token) {
        var subscriber = new TextStreamSubscriber();
        events.subscribe(subscriber);
        try { subscriber.await(Duration.ofMinutes(5)); } catch (Exception ignored) {}
    }

    void outputJsonStream(Flow.Publisher<AgentEvent> events, CancellationToken token) {
        List<Map<String, Object>> all = new ArrayList<>();
        events.subscribe(new Flow.Subscriber<>() {
            Flow.Subscription sub;
            @Override public void onSubscribe(Flow.Subscription s) { sub = s; s.request(Long.MAX_VALUE); }
            @Override public void onNext(AgentEvent e) {
                if (e instanceof AgentEvent.Done d) {
                    all.add(Map.of("type", "done", "answer", d.finalAnswer(), "rounds", d.totalRounds()));
                } else if (e instanceof AgentEvent.Error err) {
                    all.add(Map.of("type", "error", "message", err.errorMessage()));
                }
            }
            @Override public void onError(Throwable t) {}
            @Override public void onComplete() { synchronized (all) { all.notifyAll(); } }
        });
        synchronized (all) {
            try { all.wait(TimeUnit.MINUTES.toMillis(5)); } catch (InterruptedException ignored) {}
        }
        try {
            ObjectMapper mapper = new ObjectMapper();
            System.out.println(mapper.writerWithDefaultPrettyPrinter().writeValueAsString(Map.of("events", all)));
        } catch (Exception ignored) {}
    }

    void outputStreamingJson(Flow.Publisher<AgentEvent> events, CancellationToken token) {
        events.subscribe(new Flow.Subscriber<>() {
            @Override public void onSubscribe(Flow.Subscription s) { s.request(Long.MAX_VALUE); }
            @Override public void onNext(AgentEvent e) {
                if (e instanceof AgentEvent.StreamToken t) {
                    System.out.println("{\"type\":\"token\",\"text\":" + jsonEscape(t.token()) + "}");
                } else if (e instanceof AgentEvent.Done d) {
                    System.out.println("{\"type\":\"done\",\"answer\":" + jsonEscape(d.finalAnswer()) + "}");
                } else if (e instanceof AgentEvent.ToolCallStart ts) {
                    System.out.println("{\"type\":\"tool_call\",\"tool\":" + jsonEscape(ts.toolName()) + "}");
                } else if (e instanceof AgentEvent.Error err) {
                    System.out.println("{\"type\":\"error\",\"message\":" + jsonEscape(err.errorMessage()) + "}");
                }
            }
            @Override public void onError(Throwable t) {}
            @Override public void onComplete() { executor.shutdownNow(); }
        });
    }

    static String jsonEscape(String s) {
        return "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "\\r").replace("\t", "\\t") + "\"";
    }

    // ---- Interactive mode ----

    void runInteractive() {
        configureAgent();

        renderer.banner(VERSION);
        renderer.info("Chorus Code — Java-native Claude Code rival | Workspace: " + workspace);
        renderer.info("Effort: " + effortController.getLabel() + " | Permissions: " + permissionController.getMode().name().toLowerCase());
        if (skillAutoLoader != null && skillAutoLoader.count() > 0) {
            renderer.info("Loaded " + skillAutoLoader.count() + " skills from .chorus/skills/");
        }
        renderer.info("Type /help for commands. /exit to quit.");

        if (cliArgs.planMode()) {
            planMode = true;
            renderer.info("Plan mode enabled.");
        }
        if (!"default".equals(cliArgs.permissionMode())) {
            permissionController.setMode(cliArgs.permissionMode());
        }

        try (var scanner = new Scanner(System.in)) {
            while (running.get()) {
                renderer.prompt(session.getRunId());
                if (!scanner.hasNextLine()) break;
                String input = scanner.nextLine().trim();
                if (input.isEmpty()) continue;
                if (input.startsWith("/")) {
                    handleCommand(input);
                } else {
                    handleChat(input);
                }
            }
        }

        memoryManager.saveToDisk();
        sessionStore.save(session);
        renderer.info("Saved memory and session. Goodbye.");
    }

    // ---- Configuration ----

    void configureWorkspace() {
        workspace = cliArgs.workspace() != null ? cliArgs.workspace() : Path.of("").toAbsolutePath();
        String envWs = System.getenv("CHORUS_WORKSPACE");
        if (envWs != null && !envWs.isBlank()) workspace = Path.of(envWs).toAbsolutePath();
    }

    void configureLlm() {
        ObjectMapper mapper = new ObjectMapper().registerModule(new JavaTimeModule());
        String provider = System.getenv().getOrDefault("CHORUS_PROVIDER",
                System.getProperty("chorus.llm.provider", "openai"));
        model = cliArgs.modelFlag() != null ? cliArgs.modelFlag()
                : System.getenv().getOrDefault("CHORUS_MODEL", "gpt-4o");

        if ("mock".equalsIgnoreCase(provider) || System.getProperty("chorus.dev") != null) {
            renderer.info("Using Mock LLM provider");
            llmClient = new MockLlmClient(
                    List.of(MockLlmClient.ResponseScript.text("I am Chorus Code. Ready to help.")),
                    Duration.ofMillis(10));
            model = "mock-1";
            return;
        }

        HttpClient httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(30)).version(HttpClient.Version.HTTP_2).build();
        ProviderRegistry registry = new ProviderRegistry(httpClient, mapper, RetryPolicy.DEFAULT, CircuitBreaker.defaults());

        String key = System.getenv("OPENAI_API_KEY");
        if (key != null && !key.isBlank()) {
            registry.registerOpenAi("default", "https://api.openai.com/v1", key, null);
        } else {
            key = System.getenv("ANTHROPIC_API_KEY");
            if (key != null && !key.isBlank()) {
                registry.registerAnthropic("default", key);
            } else {
                key = System.getenv("GEMINI_API_KEY");
                if (key != null && !key.isBlank()) {
                    registry.registerGemini("default", key);
                } else {
                    renderer.warn("No API key. Set OPENAI/ANTHROPIC/GEMINI_API_KEY. Using mock.");
                    llmClient = MockLlmClient.defaults();
                    model = "mock-1";
                    return;
                }
            }
        }
        llmClient = registry.get("default");
    }

    void configureTools() {
        toolRegistry = new ToolRegistry();
        toolRegistry.register(new FilesystemTool(workspace));
        toolRegistry.register(new GitTool(workspace));
        toolRegistry.register(new WebSearchTool(Duration.ofSeconds(30)));
        toolRegistry.register(new ShellTool(List.of(
                "ls", "cat", "grep", "find", "echo", "pwd", "wc", "head", "tail", "sort", "uniq", "diff",
                "tree", "file", "stat", "du", "df", "which", "env", "printenv", "uname", "date", "ps",
                "pgrep", "kill", "curl", "wget", "mkdir", "cp", "mv", "rm", "chmod", "tar", "gzip",
                "zip", "unzip", "python", "python3", "node", "npm", "pnpm", "yarn", "java", "javac",
                "mvn", "gradle", "./gradlew", "cargo", "go", "pip", "pip3", "make", "cmake",
                "docker", "git", "gh", "ssh", "sh", "bash", "zsh"
        ), Duration.ofSeconds(30)));
    }

    void loadSkills() {
        Path dir = workspace.resolve(".chorus/skills");
        if (Files.isDirectory(dir)) {
            skillAutoLoader = new SkillAutoLoader(dir);
            skillAutoLoader.scan();
        } else {
            skillAutoLoader = new SkillAutoLoader(dir);
        }
    }

    void configureMemory() {
        Path memDir = workspace.resolve(".chorus");
        try { Files.createDirectories(memDir); } catch (IOException ignored) {}
    }

    void configureEffort(CliArgs cliArgs) {
        int maxTurns = cliArgs.maxTurns();
        if (maxTurns > 0) {
            if (maxTurns <= 10) effortController.setLevel(EffortController.Level.FAST);
            else if (maxTurns <= 25) effortController.setLevel(EffortController.Level.NORMAL);
            else if (maxTurns <= 50) effortController.setLevel(EffortController.Level.HIGH);
            else effortController.setLevel(EffortController.Level.XHIGH);
        }
        compactionMiddleware = new CompactionMiddleware(4000);
    }

    void loadProjectContext() {
        Path agentsMd = workspace.resolve("AGENTS.md");
        if (Files.exists(agentsMd)) {
            try {
                session.setAgentsMd(Files.readString(agentsMd));
                renderer.success("Loaded AGENTS.md (" + session.getAgentsMd().lines().count() + " lines)");
            } catch (IOException ignored) {}
        }
        Path claudeMd = workspace.resolve("CLAUDE.md");
        if (Files.exists(claudeMd)) {
            try {
                session.setClaudeMd(Files.readString(claudeMd));
                renderer.success("Loaded CLAUDE.md (" + session.getClaudeMd().lines().count() + " lines)");
            } catch (IOException ignored) {}
        }
    }

    // ---- Agent Configuration ----

    void configureAgent() {
        executor = Executors.newVirtualThreadPerTaskExecutor();
        List<Middleware> middlewares = new ArrayList<>();
        middlewares.add(session.getAgentMdMiddleware());
        middlewares.add(checkpointManager.toMiddleware());
        middlewares.add(compactionMiddleware);
        middlewares.add(permissionController.toMiddleware());
        middlewares.add(goalTracker.toMiddleware());

        String systemPrompt = buildSystemPrompt();
        agentLoop = new AgentLoop("chorus-cli", systemPrompt, llmClient, model,
                effortController.getTemperature(), effortController.getMaxTokens(),
                effortController.getMaxRounds(), middlewares, null, executor);
    }

    String buildSystemPrompt() {
        StringBuilder sb = new StringBuilder(session.getSystemPrompt());
        if (skillAutoLoader != null) {
            String skillsCtx = skillAutoLoader.getSkillsContext();
            if (!skillsCtx.isEmpty()) sb.append("\n\n").append(skillsCtx);
        }
        String memCtx = memoryManager.getMemoryContext();
        if (!memCtx.isEmpty()) sb.append("\n\n").append(memCtx);
        return sb.toString();
    }

    // ---- Chat Handler ----

    void handleChat(String input) {
        session.addToHistory(Message.user(input));
        memoryManager.store(Message.user(input), estimateTokens(input));

        CancellationToken token = CancellationToken.create();
        long startMs = System.currentTimeMillis();

        if (planMode) {
            String planPrompt = "You are in PLAN MODE. Analyze the task and present a detailed plan. "
                    + "Do NOT make any file changes, do NOT run destructive commands. "
                    + "Read files for context, then propose your approach. "
                    + "End with a clear, numbered plan. The user's request: " + input;
            session.addToHistory(Message.user(planPrompt));
        }

        configureAgent();
        List<ToolDefinition> toolDefs = ToolWiring.toToolDefinitions(toolRegistry);
        var events = agentLoop.run(session.getRunId(), input, toolDefs, token);

        var subscriber = new CliStreamSubscriber(renderer, session, toolRegistry, token, planMode, permissionController);
        events.subscribe(subscriber);

        try {
            subscriber.awaitCompletion(Duration.ofMinutes(5));
        } catch (Exception e) {
            renderer.error("Error: " + e.getMessage());
        }

        long elapsed = System.currentTimeMillis() - startMs;
        int tokens = subscriber.getTokenCount();
        renderer.dim("  (" + elapsed + "ms, " + tokens + " tokens, effort: " + effortController.getLabel() + ")");

        String finalAnswer = subscriber.getFinalAnswer();
        if (finalAnswer != null && !finalAnswer.isEmpty()) {
            session.addToHistory(Message.assistant(finalAnswer));
            memoryManager.store(Message.assistant(finalAnswer), estimateTokens(finalAnswer));
        }

        checkpointManager.save(session.getRunId(), session.getHistory(), 0);

        if (planMode && finalAnswer != null) {
            handlePlanApproval(finalAnswer);
        } else if (goalTracker.isActive()) {
            boolean goalMet = goalTracker.checkGoal(session.getHistory());
            if (!goalMet) {
                renderer.warn("Goal not met: " + goalTracker.getGoal());
                renderer.dim("  Continuing...");
                handleChat("Continue working on: " + goalTracker.getGoal());
            } else {
                renderer.success("Goal met: " + goalTracker.getGoal());
                goalTracker.clear();
            }
        }

        if (compactionMiddleware.lastEstimate() > 8000) {
            renderer.warn("Context window filling up (" + compactionMiddleware.lastEstimate()
                    + " tokens). Consider /compact or /clear.");
        }
    }

    void handlePlanApproval(String plan) {
        renderer.divider();
        renderer.warn("Plan mode: review the plan above.");
        renderer.dim("  [a]pprove and execute  [e]dit  [r]eject  [s]kip");

        try (var scanner = new Scanner(System.in)) {
            while (true) {
                renderer.planPrompt();
                if (!scanner.hasNextLine()) break;
                switch (scanner.nextLine().trim().toLowerCase()) {
                    case "a" -> { renderer.success("Plan approved. Executing..."); planMode = false; return; }
                    case "e" -> { renderer.info("Edit the plan, then type 'a'."); return; }
                    case "r" -> { renderer.warn("Plan rejected."); return; }
                    case "s" -> { renderer.dim("Plan skipped. No changes made."); return; }
                }
            }
        }
    }

    // ---- Command Handler ----

    void handleCommand(String input) {
        String[] parts = input.split("\\s+", 2);
        String cmd = parts[0];
        String args = parts.length > 1 ? parts[1] : "";

        switch (cmd) {
            case "/help" -> renderer.showHelp();
            case "/exit", "/quit" -> {
                memoryManager.saveToDisk();
                sessionStore.save(session);
                renderer.info("Saved memory/session. Goodbye.");
                running.set(false);
            }
            case "/clear" -> { session.clearHistory(); memoryManager.clear(); renderer.info("History and memory cleared."); }

            // Plan & Auto
            case "/plan" -> { planMode = true; renderer.info("Plan mode enabled."); if (!args.isEmpty()) handleChat(args); }
            case "/auto" -> { planMode = false; renderer.info("Auto mode — will make changes directly."); }

            // Effort
            case "/effort" -> setEffort(args);
            case "/fast" -> {
                effortController.setFast(!effortController.isFast());
                renderer.info("Fast mode: " + (effortController.isFast() ? "ON (2.5× speed, cheaper)" : "OFF"));
            }

            // Permissions
            case "/permissions" -> setPermissions(args);

            // Goal
            case "/goal" -> {
                if (args.isEmpty()) { goalTracker.clear(); renderer.info("Goal cleared."); }
                else {
                    goalTracker.setGoal(args, (goal, history) -> {
                        String last = history.isEmpty() ? "" : history.get(history.size() - 1).content();
                        return last.toLowerCase().contains("goal met") || last.toLowerCase().contains("completed");
                    });
                    renderer.info("Goal set: " + args);
                }
            }

            // Compaction
            case "/compact" -> {
                if (args.equals("off")) { compactionMiddleware.disableAutoCompact(); renderer.info("Auto-compaction disabled."); }
                else if (args.equals("on")) { compactionMiddleware.enableAutoCompact(); renderer.info("Auto-compaction enabled."); }
                else {
                    renderer.info("Compacting...");
                    handleChat("Please summarize our conversation so far, keeping the key decisions and context.");
                }
            }

            // Checkpoints
            case "/checkpoints" -> showCheckpoints(args);
            case "/rewind" -> rewindCheckpoint(args);

            // Memory
            case "/memory" -> handleMemory(args);

            // Skills
            case "/skill" -> invokeSkill(args);
            case "/skills" -> listSkills();
            case "/reload-skills" -> { skillAutoLoader.scan(); renderer.info("Reloaded " + skillAutoLoader.count() + " skills."); }

            // Legacy skill commands
            case "/review" -> skills.review(args);
            case "/debug" -> skills.debug(args);
            case "/test" -> skills.test(args);
            case "/refactor" -> skills.refactor(args);
            case "/docs" -> skills.docs(args);

            // Tools
            case "/tools" -> renderer.showTools(toolRegistry);
            case "/git-status" -> executeTool("git", Map.of("operation", "git_status"), "git status");
            case "/git-diff" -> executeTool("git", Map.of("operation", "git_diff"), "git diff");
            case "/git-log" -> executeTool("git", Map.of("operation", "git_log", "maxCount", 10), "git log");
            case "/git-branch" -> executeTool("git", Map.of("operation", "git_branch"), "git branch");
            case "/search" -> {
                if (!args.isEmpty()) executeTool("web_search", Map.of("operation", "web_search", "query", args), "web search");
                else renderer.warn("Usage: /search <query>");
            }

            // Session
            case "/save" -> { sessionStore.save(session); memoryManager.saveToDisk(); renderer.success("Saved."); }
            case "/load" -> {
                var loaded = sessionStore.load();
                loaded.ifPresentOrElse(s -> renderer.success("Loaded " + s.getHistory().size() + " messages"),
                        () -> renderer.warn("No saved session."));
            }

            // Info
            case "/tokens" -> renderer.info("Est. tokens: " + compactionMiddleware.lastEstimate());
            case "/cost" -> renderer.info("Provider: " + llmClient.providerName()
                    + " | Model: " + model + " | Effort: " + effortController.getLabel());
            case "/version" -> renderer.info("Chorus Code v" + VERSION);
            case "/mcp" -> renderer.info("MCP: set CHORUS_MCP_URL env var to connect. Built-in tools active.");
            case "/spawn" -> renderer.info("Sub-agent: /spawn is available. Use for parallel research/investigation.");

            // Graph workflow
            case "/graph-define" -> graphDefine(args);
            case "/graph-run" -> graphRun(args);

            // RAG
            case "/index" -> {
                int n = 200;
                try { if (!args.isEmpty()) n = Integer.parseInt(args); } catch (NumberFormatException ignored) {}
                if (integrations != null) integrations.indexCodebase(n);
            }
            case "/rag-search" -> {
                if (integrations != null) {
                    var results = integrations.ragSearch(args, 5);
                    renderer.info("RAG results: " + results.size() + " chunks");
                    for (int i = 0; i < Math.min(results.size(), 5); i++) {
                        renderer.dim("  [" + i + "] " + results.get(i).chunk().documentId() + " score=" + String.format("%.3f", results.get(i).score()));
                    }
                }
            }

            // Swarm
            case "/swarm" -> {
                if (integrations != null && integrations.swarmOrchestrator != null) {
                    renderer.info("Swarm running: " + args);
                } else {
                    integrations.initSwarm();
                    renderer.info("Swarm started with 3 agents.");
                }
            }

            // Evaluations
            case "/eval" -> runEval(args);
            case "/benchmark" -> runBenchmark(args);

            // Harness
            case "/harness" -> runHarness(args);

            // A2A
            case "/a2a-card" -> showA2aCard();

            default -> {
                if (skillAutoLoader.getSkillContent(cmd.substring(1)) != null && args.isEmpty()) {
                    invokeSkill(cmd.substring(1));
                } else {
                    renderer.warn("Unknown: " + cmd + ". Type /help.");
                }
            }
        }
    }

    void setEffort(String args) {
        switch (args.toLowerCase()) {
            case "high" -> { effortController.setLevel(EffortController.Level.HIGH); configureAgent(); }
            case "xhigh" -> { effortController.setLevel(EffortController.Level.XHIGH); configureAgent(); }
            case "normal" -> { effortController.setLevel(EffortController.Level.NORMAL); configureAgent(); }
            default -> renderer.warn("Usage: /effort [high|xhigh|normal]");
        }
        renderer.info("Effort: " + effortController.getLabel() + " (temp="
                + effortController.getTemperature() + ", maxTokens=" + effortController.getMaxTokens() + ")");
    }

    void setPermissions(String args) {
        permissionController.setMode(args);
        renderer.info("Permissions: " + permissionController.getMode().name().toLowerCase());
    }

    void showCheckpoints(String args) {
        var refs = checkpointManager.list(session.getRunId());
        if (refs.isEmpty()) { renderer.info("No checkpoints."); return; }
        renderer.info("Checkpoints for run " + session.getRunId() + ":");
        for (int i = 0; i < Math.min(refs.size(), 20); i++) {
            var ref = refs.get(i);
            renderer.dim("  #" + ref.sequenceNumber() + " — " + new java.util.Date(ref.timestamp()));
        }
    }

    void rewindCheckpoint(String args) {
        AgentState state;
        if (args.isEmpty()) {
            state = checkpointManager.loadLatest(session.getRunId());
        } else {
            try {
                long seq = Long.parseLong(args);
                state = checkpointManager.loadLatest(session.getRunId());
            } catch (NumberFormatException e) {
                renderer.error("Invalid checkpoint number: " + args);
                return;
            }
        }
        if (state == null) { renderer.warn("No checkpoint to restore."); return; }
        renderer.success("Rewound to checkpoint. " + state.history().size() + " messages restored.");
    }

    void handleMemory(String args) {
        if (args.startsWith("search ")) {
            String query = args.substring(7);
            var results = memoryManager.search(query, 5);
            renderer.info("Memory search: " + query);
            for (int i = 0; i < results.size(); i++) {
                renderer.dim("  [" + i + "] " + results.get(i).content().substring(0, Math.min(200, results.get(i).content().length())));
            }
        } else if (args.equals("clear")) {
            memoryManager.clear();
            renderer.info("Memory cleared.");
        } else if (args.equals("save")) {
            memoryManager.saveToDisk();
            renderer.success("Memory saved to .chorus/memory.json");
        } else {
            renderer.info("Memory: " + memoryManager.currentTokens() + " tokens in short-term. /memory search <q> | clear | save");
        }
    }

    void invokeSkill(String name) {
        String content = skillAutoLoader.getSkillContent(name);
        if (content == null) {
            var skill = skillAutoLoader.get(name);
            if (skill != null && skill.disableModelInvocation()) {
                content = skill.content();
            } else {
                renderer.warn("Skill not found: " + name + ". Available: " + skillAutoLoader.getAll().keySet());
                return;
            }
        }
        renderer.info("Invoking skill: /" + name);
        handleChat(content);
    }

    void listSkills() {
        var all = skillAutoLoader.getAll();
        if (all.isEmpty()) {
            renderer.info("No skills found. Create SKILL.md files in .chorus/skills/<name>/SKILL.md");
            return;
        }
        renderer.info("Loaded Skills (" + all.size() + "):");
        for (var entry : all.entrySet()) {
            var s = entry.getValue();
            String status = s.disableModelInvocation() ? " [manual]" : " [auto]";
            renderer.dim("  /" + s.name() + status + " — " + s.description());
        }
    }

    void executeTool(String toolName, Map<String, Object> args, String label) {
        renderer.dim("Running " + label + "...");
        var result = toolRegistry.execute(toolName, args, CancellationToken.create());
        switch (result) {
            case Result.Ok(var output) -> renderer.text(output.content());
            case Result.Err(var error) -> renderer.error("Error: " + error);
        }
    }

    int estimateTokens(String text) {
        return (int) Math.ceil(text.length() / 3.5);
    }

    int estimateTokens(List<Message> messages) {
        return messages.stream().mapToInt(m -> estimateTokens(m.content())).sum();
    }

    // ---- Stream Subscriber ----

    static class CliStreamSubscriber implements Flow.Subscriber<AgentEvent> {
        private final CliRenderer renderer;
        private final CliSession session;
        private final ToolRegistry toolRegistry;
        private final CancellationToken token;
        private final boolean planMode;
        private final PermissionController permissions;
        private final CompletableFuture<Void> completionFuture = new CompletableFuture<>();
        private final AtomicReference<String> finalAnswer = new AtomicReference<>();
        private final AtomicInteger tokenCount = new AtomicInteger(0);

        CliStreamSubscriber(CliRenderer renderer, CliSession session, ToolRegistry toolRegistry,
                            CancellationToken token, boolean planMode, PermissionController permissions) {
            this.renderer = renderer;
            this.session = session;
            this.toolRegistry = toolRegistry;
            this.token = token;
            this.planMode = planMode;
            this.permissions = permissions;
        }

        @Override public void onSubscribe(Flow.Subscription s) { s.request(Long.MAX_VALUE); }

        @Override
        public void onNext(AgentEvent event) {
            switch (event) {
                case AgentEvent.StreamToken t -> {
                    renderer.text(t.token());
                    tokenCount.incrementAndGet();
                }
                case AgentEvent.ToolCallStart t -> {
                    boolean needsApproval = permissions.needsApproval(t.toolName());
                    String prefix = needsApproval ? " [tool?:" : " [tool:";
                    renderer.dim("\n" + prefix + t.toolName() + "] ");
                }
                case AgentEvent.ToolCallDone t -> renderer.success("  [done: " + t.toolName() + "]");
                case AgentEvent.ToolCallError t -> renderer.error("  [error: " + t.toolName() + " - " + t.errorMessage() + "]");
                case AgentEvent.HitlRequested h -> renderer.warn("  [HITL: " + h.toolName() + " — approve? use /approve " + h.gateId() + "]");
                case AgentEvent.Done d -> finalAnswer.set(d.finalAnswer());
                case AgentEvent.Error e -> renderer.error("\n  [" + e.errorType() + ": " + e.errorMessage() + "]");
                case AgentEvent.CompactionTriggered ct ->
                        renderer.dim("  [compacted: " + ct.tokensBefore() + "→" + ct.tokensAfter() + " tokens]");
                default -> {}
            }
        }

        @Override public void onError(Throwable t) { renderer.error("Stream error: " + t.getMessage()); completionFuture.completeExceptionally(t); }
        @Override public void onComplete() { completionFuture.complete(null); }

        void awaitCompletion(Duration timeout) throws InterruptedException, ExecutionException, TimeoutException {
            completionFuture.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
        }
        String getFinalAnswer() { return finalAnswer.get(); }
        int getTokenCount() { return tokenCount.get(); }
    }

    // ---- Text Stream Subscriber ----

    static class TextStreamSubscriber implements Flow.Subscriber<AgentEvent> {
        private final CountDownLatch latch = new CountDownLatch(1);

        @Override public void onSubscribe(Flow.Subscription s) { s.request(Long.MAX_VALUE); }
        @Override public void onNext(AgentEvent event) {
            if (event instanceof AgentEvent.StreamToken t) System.out.print(t.token());
            if (event instanceof AgentEvent.Done) System.out.println();
        }
        @Override public void onError(Throwable t) { latch.countDown(); }
        @Override public void onComplete() { latch.countDown(); }

        void await(Duration timeout) throws InterruptedException { latch.await(timeout.toMillis(), TimeUnit.MILLISECONDS); }
    }

    // ── Graph / Evals / Harness / A2A ─────────────────────────

    void graphDefine(String args) {
        renderer.info("Graph: use @GraphWorkflow/@GraphNode or REST API /api/graph/define");
    }
    void graphRun(String args) {
        renderer.info("Graph: invoke via CompiledGraph. View: http://localhost:8080/chorus/visualizer (dev)");
    }
    void runEval(String args) {
        renderer.info("Evals: use EvalRunner. Scorers: exact_match, contains, llm_judge, semantic_similarity");
    }
    void runBenchmark(String args) {
        renderer.info("Benchmarks: RAG_BENCHMARK, TOOL_USE_BENCHMARK, REASONING_BENCHMARK");
    }
    void runHarness(String args) {
        renderer.info("Harness: autonomous execution via HarnessEngine with WorkerPool + SafetyAuditor");
    }
    void showA2aCard() {
        renderer.info("A2A: Google Agent-to-Agent protocol. REST: GET /api/a2a/agent-card");
    }

    // ---- Help ----

    static void showHelpAndExit() {
        System.out.println("""
                Chorus Code — Java-native Claude Code rival
                
                USAGE:
                  java -jar chorus-code.jar                  Interactive REPL
                  java -jar chorus-code.jar -p "fix the bug"  Non-interactive mode
                  echo "error log" | java -jar chorus-code.jar -p --output-format json
                
                FLAGS:
                  -p, --print            Non-interactive mode (read from stdin if no arg)
                  --output-format fmt     json, stream-json, or text (default)
                  --model model           Model name override
                  --allowed-tools list    Comma-separated tool names
                  --max-turns N           Max agent rounds (default 25)
                  --plan                  Start in plan mode
                  --permission-mode mode  default, auto, acceptEdits, strict, bypass
                  --verbose               Verbose output
                  --workspace path        Root directory
                  --help, -h              This help
                
                INTERACTIVE COMMANDS:
                  /help, /exit, /clear, /plan, /auto, /tools
                  /effort [normal|high|xhigh], /fast
                  /permissions [default|auto|acceptEdits|strict|bypass]
                  /goal <condition>, /compact [on|off]
                  /checkpoints, /rewind [seq]
                  /memory [search <q>|clear|save]
                  /skills, /skill <name>, /reload-skills
                  /index <n>, /rag-search <q>, /swarm <task>
                  /review, /debug, /test, /refactor, /docs
                  /git-status, /git-diff, /git-log, /git-branch
                  /search <query>, /save, /load, /tokens, /cost, /version
                  /graph-define, /graph-run, /eval, /benchmark, /harness, /a2a-card
                
                ENV VARS:
                  OPENAI_API_KEY, ANTHROPIC_API_KEY, GEMINI_API_KEY
                  CHORUS_PROVIDER (openai|anthropic|gemini|mock)
                  CHORUS_MODEL, CHORUS_WORKSPACE
                """);
    }
}
