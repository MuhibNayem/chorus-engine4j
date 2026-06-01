package com.chorus.engine.agent.loop;

import com.chorus.engine.agent.ToolExecutor;
import com.chorus.engine.agent.hitl.HitlGate;
import com.chorus.engine.agent.middleware.Middleware;
import com.chorus.engine.llm.LlmClient;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Immutable configuration for an {@link AgentLoop}, created via {@link Builder}.
 *
 * <pre>{@code
 * AgentLoop loop = AgentLoopConfig
 *     .builder("my-agent", llmClient)
 *     .systemPrompt("You are a helpful AI assistant.")
 *     .model("gpt-4o")
 *     .maxRounds(15)
 *     .toolExecutor(ToolExecutor.of(toolRegistry))
 *     .middleware(new CompactionMiddleware())
 *     .build()
 *     .createLoop();
 * }</pre>
 */
public final class AgentLoopConfig {

    public final String agentId;
    public final String systemPrompt;
    public final LlmClient llmClient;
    public final String model;
    public final double temperature;
    public final int maxTokens;
    public final int maxRounds;
    public final List<Middleware> middlewares;
    public final @Nullable HitlGate hitlGate;
    public final ExecutorService executor;
    public final @Nullable ToolExecutor toolExecutor;
    public final Set<String> sensitiveTools;

    private AgentLoopConfig(Builder b) {
        this.agentId        = Objects.requireNonNull(b.agentId,    "agentId");
        this.systemPrompt   = Objects.requireNonNull(b.systemPrompt, "systemPrompt");
        this.llmClient      = Objects.requireNonNull(b.llmClient,   "llmClient");
        this.model          = Objects.requireNonNull(b.model,       "model");
        this.temperature    = b.temperature;
        this.maxTokens      = b.maxTokens;
        this.maxRounds      = b.maxRounds;
        this.middlewares    = List.copyOf(b.middlewares);
        this.hitlGate       = b.hitlGate;
        this.executor       = b.executor != null ? b.executor : Executors.newVirtualThreadPerTaskExecutor();
        this.toolExecutor   = b.toolExecutor;
        this.sensitiveTools = b.sensitiveTools != null ? Set.copyOf(b.sensitiveTools) : AgentLoop.DEFAULT_SENSITIVE_TOOLS;
    }

    /** Create an {@link AgentLoop} from this configuration. */
    public @NonNull AgentLoop createLoop() {
        return new AgentLoop(this, List.of());
    }

    /**
     * Create an {@link AgentLoop} substituting a different {@link LlmClient} and
     * prepending extra middlewares. Used by
     * {@link com.chorus.engine.agent.selfhealing.SelfHealingAgentLoop#wrap} to inject
     * the healing proxy without copying all builder fields manually.
     */
    public @NonNull AgentLoop createLoop(
            @NonNull LlmClient proxyClient,
            @NonNull List<Middleware> extraMiddlewares) {
        AgentLoopConfig proxied = AgentLoopConfig.builder(agentId, proxyClient)
            .systemPrompt(systemPrompt).model(model)
            .temperature(temperature).maxTokens(maxTokens).maxRounds(maxRounds)
            .middlewares(new java.util.ArrayList<>(middlewares))
            .executor(executor).sensitiveTools(sensitiveTools)
            .build();
        if (hitlGate    != null) return new AgentLoop(withHitlGate(proxied), extraMiddlewares);
        if (toolExecutor!= null) return new AgentLoop(withToolExecutor(proxied), extraMiddlewares);
        return new AgentLoop(proxied, extraMiddlewares);
    }

    private AgentLoopConfig withHitlGate(AgentLoopConfig base) {
        return builder(base.agentId, base.llmClient)
            .systemPrompt(base.systemPrompt).model(base.model)
            .temperature(base.temperature).maxTokens(base.maxTokens).maxRounds(base.maxRounds)
            .middlewares(new java.util.ArrayList<>(base.middlewares))
            .executor(base.executor).sensitiveTools(base.sensitiveTools)
            .hitlGate(this.hitlGate).toolExecutor(this.toolExecutor)
            .build();
    }

    private AgentLoopConfig withToolExecutor(AgentLoopConfig base) {
        return builder(base.agentId, base.llmClient)
            .systemPrompt(base.systemPrompt).model(base.model)
            .temperature(base.temperature).maxTokens(base.maxTokens).maxRounds(base.maxRounds)
            .middlewares(new java.util.ArrayList<>(base.middlewares))
            .executor(base.executor).sensitiveTools(base.sensitiveTools)
            .hitlGate(this.hitlGate).toolExecutor(this.toolExecutor)
            .build();
    }

    public static @NonNull Builder builder(@NonNull String agentId, @NonNull LlmClient llmClient) {
        return new Builder(agentId, llmClient);
    }

    public static final class Builder {
        private final String agentId;
        private final LlmClient llmClient;
        private String systemPrompt  = "You are a helpful AI assistant.";
        private String model         = "gpt-4o";
        private double temperature   = 0.7;
        private int maxTokens        = 4096;
        private int maxRounds        = 10;
        private final List<Middleware> middlewares = new ArrayList<>();
        private HitlGate hitlGate;
        private ExecutorService executor;
        private ToolExecutor toolExecutor;
        private Set<String> sensitiveTools;

        private Builder(String agentId, LlmClient llmClient) {
            this.agentId    = agentId;
            this.llmClient  = llmClient;
        }

        public Builder systemPrompt(@NonNull String p)    { this.systemPrompt = p; return this; }
        public Builder model(@NonNull String m)           { this.model = m; return this; }
        public Builder temperature(double t)              { this.temperature = t; return this; }
        public Builder maxTokens(int n)                   { this.maxTokens = n; return this; }
        public Builder maxRounds(int n)                   { this.maxRounds = n; return this; }
        public Builder hitlGate(@NonNull HitlGate g)     { this.hitlGate = g; return this; }
        public Builder executor(@NonNull ExecutorService e){ this.executor = e; return this; }
        public Builder toolExecutor(@NonNull ToolExecutor t){ this.toolExecutor = t; return this; }
        public Builder sensitiveTools(@NonNull Set<String> s){ this.sensitiveTools = s; return this; }

        public Builder middleware(@NonNull Middleware mw) {
            this.middlewares.add(mw);
            return this;
        }
        public Builder middlewares(@NonNull List<Middleware> list) {
            this.middlewares.addAll(list);
            return this;
        }

        public @NonNull AgentLoopConfig build() { return new AgentLoopConfig(this); }
    }
}
