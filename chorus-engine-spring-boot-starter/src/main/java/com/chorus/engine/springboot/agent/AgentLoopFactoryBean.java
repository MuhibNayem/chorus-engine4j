package com.chorus.engine.springboot.agent;

import com.chorus.engine.agent.loop.AgentLoop;
import com.chorus.engine.llm.LlmClient;
import com.chorus.engine.springboot.ChorusProperties;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.BeanFactory;
import org.springframework.beans.factory.BeanFactoryAware;
import org.springframework.beans.factory.FactoryBean;

import java.util.List;
import java.util.concurrent.ExecutorService;

/**
 * Spring {@link FactoryBean} that creates an {@link AgentLoop} instance,
 * resolving defaults from {@link ChorusProperties} at singleton-instantiation
 * time rather than at bean-definition-registration time.
 *
 * <p>This avoids triggering early initialisation of
 * {@code @ConfigurationProperties} beans during
 * {@code postProcessBeanDefinitionRegistry}, which can break property binding.
 *
 * <p>Resolution priority (highest to lowest):
 * <ol>
 *   <li>Value explicitly provided on the {@code @Agent} annotation</li>
 *   <li>Value from {@code chorus.*} properties (via {@link ChorusProperties})</li>
 *   <li>Framework default</li>
 * </ol>
 */
public final class AgentLoopFactoryBean implements FactoryBean<AgentLoop>, BeanFactoryAware {

    private @Nullable BeanFactory beanFactory;

    private @NonNull String agentName = "";
    private @NonNull String systemPrompt = "";
    private @NonNull String agentBeanName = "";
    private @NonNull String model = "";
    private double temperature = -1.0;
    private int maxTokens = -1;
    private int maxRounds = -1;

    public void setAgentName(@NonNull String agentName) {
        this.agentName = agentName;
    }

    public void setSystemPrompt(@NonNull String systemPrompt) {
        this.systemPrompt = systemPrompt;
    }

    public void setAgentBeanName(@NonNull String agentBeanName) {
        this.agentBeanName = agentBeanName;
    }

    public void setModel(@NonNull String model) {
        this.model = model;
    }

    public void setTemperature(double temperature) {
        this.temperature = temperature;
    }

    public void setMaxTokens(int maxTokens) {
        this.maxTokens = maxTokens;
    }

    public void setMaxRounds(int maxRounds) {
        this.maxRounds = maxRounds;
    }

    @Override
    public void setBeanFactory(@NonNull BeanFactory beanFactory) throws BeansException {
        this.beanFactory = beanFactory;
    }

    @Override
    public @NonNull AgentLoop getObject() throws Exception {
        if (beanFactory == null) {
            throw new IllegalStateException("BeanFactory has not been set");
        }

        LlmClient llmClient = beanFactory.getBean("llmClient", LlmClient.class);
        ExecutorService executor = beanFactory.getBean("chorusExecutor", ExecutorService.class);

        String resolvedModel = resolveModel();
        double resolvedTemperature = resolveTemperature();
        int resolvedMaxTokens = resolveMaxTokens();
        int resolvedMaxRounds = resolveMaxRounds();

        return new AgentLoop(
            agentName,
            systemPrompt,
            llmClient,
            resolvedModel,
            resolvedTemperature,
            resolvedMaxTokens,
            resolvedMaxRounds,
            List.of(),   // middlewares — extension point for future ordered registration
            null,        // hitlGate
            executor
        );
    }

    @Override
    public @NonNull Class<?> getObjectType() {
        return AgentLoop.class;
    }

    @Override
    public boolean isSingleton() {
        return true;
    }

    // -------------------------------------------------------------------------
    // Default resolution — ChorusProperties > hard-coded fallback
    // -------------------------------------------------------------------------

    private @NonNull String resolveModel() {
        if (!model.isEmpty()) {
            return model;
        }
        ChorusProperties.Llm llm = resolveLlmProperties();
        if (llm != null && llm.getModel() != null && !llm.getModel().isEmpty()) {
            return llm.getModel();
        }
        return "gpt-4o";
    }

    private double resolveTemperature() {
        if (temperature >= 0.0) {
            return temperature;
        }
        ChorusProperties.Llm llm = resolveLlmProperties();
        if (llm != null) {
            return llm.getTemperature();
        }
        return 0.7;
    }

    private int resolveMaxTokens() {
        if (maxTokens >= 0) {
            return maxTokens;
        }
        ChorusProperties.Llm llm = resolveLlmProperties();
        if (llm != null) {
            return llm.getMaxTokens();
        }
        return 4096;
    }

    private int resolveMaxRounds() {
        if (maxRounds >= 0) {
            return maxRounds;
        }
        ChorusProperties.Agent agent = resolveAgentProperties();
        if (agent != null) {
            return agent.getMaxRounds();
        }
        return 10;
    }

    private ChorusProperties.@Nullable Llm resolveLlmProperties() {
        ChorusProperties props = resolveChorusProperties();
        return props != null ? props.getLlm() : null;
    }

    private ChorusProperties.@Nullable Agent resolveAgentProperties() {
        ChorusProperties props = resolveChorusProperties();
        return props != null ? props.getAgent() : null;
    }

    private @Nullable ChorusProperties resolveChorusProperties() {
        if (beanFactory == null) {
            return null;
        }
        if (beanFactory.containsBean("chorusProperties")) {
            try {
                return beanFactory.getBean("chorusProperties", ChorusProperties.class);
            } catch (BeansException e) {
                // ChorusProperties bean exists but is not yet available — use defaults
                return null;
            }
        }
        return null;
    }
}
