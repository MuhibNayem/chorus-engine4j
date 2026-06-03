package com.chorus.engine.springboot.swarm;

import com.chorus.engine.annotation.SwarmAgent;
import com.chorus.engine.llm.LlmClient;
import com.chorus.engine.springboot.ChorusProperties;
import com.chorus.engine.swarm.AgentDefinition;
import com.chorus.engine.swarm.HandoffOrchestrator;
import com.chorus.engine.swarm.PlannerExecutorOrchestrator;
import com.chorus.engine.swarm.SupervisorOrchestrator;
import com.chorus.engine.swarm.SwarmConfig;
import com.chorus.engine.swarm.SwarmOrchestrator;
import com.chorus.engine.tools.Tool;
import com.chorus.engine.tools.ToolRegistry;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.BeanFactory;
import org.springframework.beans.factory.BeanFactoryAware;
import org.springframework.beans.factory.FactoryBean;
import org.springframework.core.annotation.AnnotationUtils;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;

/**
 * Spring {@link FactoryBean} that creates a {@link SwarmOrchestrator} instance,
 * deferring {@link ChorusProperties} lookup and {@link Tool} resolution to
 * singleton-instantiation time.
 *
 * <p>This avoids triggering early initialisation of
 * {@code @ConfigurationProperties} beans during
 * {@code postProcessBeanDefinitionRegistry} and ensures that all tools have
 * already been registered in the {@link ToolRegistry} before the orchestrator
 * is constructed.
 *
 * <p>Resolution priority (highest to lowest):
 * <ol>
 *   <li>Value explicitly provided on the {@code @SwarmAgent} annotation</li>
 *   <li>Value from {@code chorus.*} properties (via {@link ChorusProperties})</li>
 *   <li>Framework default</li>
 * </ol>
 */
public final class SwarmOrchestratorFactoryBean implements FactoryBean<SwarmOrchestrator>, BeanFactoryAware {

    private @Nullable BeanFactory beanFactory;
    private final @NonNull List<AgentMetadata> agents = new ArrayList<>();

    public void addAgent(@NonNull AgentMetadata metadata) {
        this.agents.add(metadata);
    }

    public void setAgents(@NonNull List<AgentMetadata> agents) {
        this.agents.clear();
        this.agents.addAll(agents);
    }

    @Override
    public void setBeanFactory(@NonNull BeanFactory beanFactory) throws BeansException {
        this.beanFactory = beanFactory;
    }

    @Override
    public @NonNull SwarmOrchestrator getObject() throws Exception {
        if (beanFactory == null) {
            throw new IllegalStateException("BeanFactory has not been set");
        }

        // Discover agents registered as individual Spring beans by SwarmAnnotationProcessor.
        // The directly-added agents (via addAgent/setAgents) are included as a fallback for
        // programmatic usage outside of the annotation-driven path.
        List<AgentMetadata> resolvedAgents = new ArrayList<>(agents);
        if (beanFactory instanceof org.springframework.beans.factory.config.ConfigurableListableBeanFactory clbf) {
            clbf.getBeansOfType(AgentMetadata.class).values().forEach(resolvedAgents::add);
        }

        if (resolvedAgents.isEmpty()) {
            throw new IllegalStateException("No swarm agents configured");
        }

        LlmClient llmClient = beanFactory.getBean("llmClient", LlmClient.class);
        ToolRegistry toolRegistry = beanFactory.getBean("toolRegistry", ToolRegistry.class);
        SwarmConfig swarmConfig = beanFactory.getBean("swarmConfig", SwarmConfig.class);
        ExecutorService executor = beanFactory.getBean("chorusExecutor", ExecutorService.class);

        Map<String, AgentDefinition> agentMap = buildAgentDefinitions(resolvedAgents, toolRegistry);
        String type = resolveOrchestratorType();

        return switch (type) {
            case "supervisor" -> {
                String supervisor = resolvedAgents.getFirst().name();
                yield new SupervisorOrchestrator(agentMap, supervisor, llmClient, toolRegistry, swarmConfig, executor);
            }
            case "planner-executor" -> {
                String planner = resolvedAgents.getFirst().name();
                yield new PlannerExecutorOrchestrator(agentMap, planner, llmClient, toolRegistry, swarmConfig, executor);
            }
            default -> new HandoffOrchestrator(agentMap, llmClient, toolRegistry, swarmConfig, executor);
        };
    }

    @Override
    public @NonNull Class<?> getObjectType() {
        return SwarmOrchestrator.class;
    }

    @Override
    public boolean isSingleton() {
        return true;
    }

    // -------------------------------------------------------------------------
    // Internal helpers
    // -------------------------------------------------------------------------

    private @NonNull Map<String, AgentDefinition> buildAgentDefinitions(
        @NonNull List<AgentMetadata> resolvedAgents, @NonNull ToolRegistry toolRegistry
    ) {
        Map<String, AgentDefinition> map = new LinkedHashMap<>();
        for (AgentMetadata meta : resolvedAgents) {
            List<Tool> tools = resolveTools(meta.toolNames(), toolRegistry);
            String model = resolveModel(meta.model());
            double temperature = resolveTemperature(meta.temperature());

            AgentDefinition def = new AgentDefinition(
                meta.name(),
                meta.instructions(),
                tools,
                model,
                meta.handoffTargets().isEmpty() ? null : meta.handoffTargets(),
                Map.of("temperature", temperature)
            );
            map.put(meta.name(), def);
        }
        return map;
    }

    private @NonNull List<Tool> resolveTools(@NonNull List<String> toolNames, @NonNull ToolRegistry toolRegistry) {
        List<Tool> tools = new ArrayList<>();
        for (String name : toolNames) {
            Tool tool = toolRegistry.find(name);
            if (tool != null) {
                tools.add(tool);
            }
            // Silently ignore missing tools — the orchestrator will report
            // "Tool not found" at runtime, which is preferable to failing
            // context startup because of a typo in an annotation.
        }
        return tools;
    }

    private @NonNull String resolveModel(@NonNull String annotatedModel) {
        if (!annotatedModel.isEmpty()) {
            return annotatedModel;
        }
        ChorusProperties props = resolveChorusProperties();
        if (props != null && props.getLlm() != null) {
            String m = props.getLlm().getModel();
            if (m != null && !m.isEmpty()) {
                return m;
            }
        }
        return "gpt-4o";
    }

    private double resolveTemperature(double annotatedTemperature) {
        if (annotatedTemperature >= 0.0) {
            return annotatedTemperature;
        }
        ChorusProperties props = resolveChorusProperties();
        if (props != null && props.getLlm() != null) {
            return props.getLlm().getTemperature();
        }
        return 0.7;
    }

    private @NonNull String resolveOrchestratorType() {
        // 1. Check for @SwarmConfig annotation on any bean
        if (beanFactory instanceof org.springframework.beans.factory.config.ConfigurableListableBeanFactory clbf) {
            for (String beanName : clbf.getBeanDefinitionNames()) {
                var bd = clbf.getBeanDefinition(beanName);
                Class<?> clazz = resolveBeanClass(bd, clbf);
                if (clazz == null) continue;
                com.chorus.engine.annotation.SwarmConfig ann = AnnotationUtils.findAnnotation(clazz, com.chorus.engine.annotation.SwarmConfig.class);
                if (ann != null) {
                    String type = ann.orchestrator();
                    if (type != null && !type.isEmpty()) {
                        return type.toLowerCase();
                    }
                }
            }
        }
        return "handoff";
    }

    private Class<?> resolveBeanClass(org.springframework.beans.factory.config.BeanDefinition bd,
                                       org.springframework.beans.factory.config.ConfigurableListableBeanFactory beanFactory) {
        String className = bd.getBeanClassName();
        if (className != null) {
            try {
                return Class.forName(className, true, beanFactory.getBeanClassLoader());
            } catch (ClassNotFoundException e) {
                return null;
            }
        }
        org.springframework.core.ResolvableType resolvableType = bd.getResolvableType();
        if (resolvableType != org.springframework.core.ResolvableType.NONE) {
            return resolvableType.resolve();
        }
        return null;
    }

    private @Nullable ChorusProperties resolveChorusProperties() {
        if (beanFactory == null) {
            return null;
        }
        if (beanFactory.containsBean("chorusProperties")) {
            try {
                return beanFactory.getBean("chorusProperties", ChorusProperties.class);
            } catch (BeansException e) {
                return null;
            }
        }
        return null;
    }

}
