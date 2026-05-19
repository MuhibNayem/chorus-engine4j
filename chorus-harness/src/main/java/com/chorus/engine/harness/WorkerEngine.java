package com.chorus.engine.harness;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;

/**
 * Dispatches tasks to agents based on {@link SemanticRouter} decisions.
 * Maintains a registry of named {@link AgentFactory} instances.
 */
public class WorkerEngine {

    private static final Logger log = LoggerFactory.getLogger(WorkerEngine.class);

    private final SemanticRouter router;
    private final Map<String, AgentFactory> agentFactories = new ConcurrentHashMap<>();
    private final String defaultAgent;
    private static final Executor VIRTUAL_EXECUTOR = Executors.newVirtualThreadPerTaskExecutor();

    /**
     * Functional interface for agent factories. Implementations receive the raw task/query
     * and return a future containing the agent's response.
     */
    @FunctionalInterface
    public interface AgentFactory {
        CompletableFuture<String> execute(String task);
    }

    public WorkerEngine(SemanticRouter router, String defaultAgent) {
        this.router = Objects.requireNonNull(router, "SemanticRouter must not be null");
        this.defaultAgent = Objects.requireNonNull(defaultAgent, "Default agent must not be null");
    }

    public WorkerEngine(SemanticRouter router) {
        this(router, "default");
    }

    /**
     * Register an agent factory under a name. The name should correspond to the
     * {@code targetAgent} field of a {@link Route}.
     */
    public void registerAgentFactory(String name, AgentFactory factory) {
        Objects.requireNonNull(name, "Agent name must not be null");
        Objects.requireNonNull(factory, "Factory must not be null");
        agentFactories.put(name, factory);
        log.debug("Registered agent factory '{}'", name);
    }

    /**
     * Remove a previously registered agent factory.
     */
    public void unregisterAgentFactory(String name) {
        agentFactories.remove(name);
        log.debug("Unregistered agent factory '{}'", name);
    }

    /**
     * Classify the task using the semantic router and delegate to the matching agent factory.
     * Falls back to the default agent when no route exceeds its threshold or when the target
     * factory is missing.
     *
     * @param task the user task/query
     * @return future containing the agent response
     */
    public CompletableFuture<String> dispatch(String task) {
        Objects.requireNonNull(task, "Task must not be null");
        return router.route(task).thenComposeAsync(result -> {
            String targetAgent = result.routeName();
            AgentFactory factory = agentFactories.get(targetAgent);

            if (factory == null) {
                factory = agentFactories.get(defaultAgent);
                if (factory == null) {
                    return CompletableFuture.failedFuture(new IllegalStateException(
                        "No agent factory registered for route '" + targetAgent
                            + "' and no default factory available"
                    ));
                }
                log.warn(
                    "Agent factory '{}' not found; falling back to default '{}'",
                    targetAgent, defaultAgent
                );
                targetAgent = defaultAgent;
            }

            log.info(
                "Dispatching task to agent '{}' (confidence={}, matchedExample='{}')",
                targetAgent, result.confidence(), result.matchedExample()
            );
            return factory.execute(task);
        }, VIRTUAL_EXECUTOR);
    }
}
