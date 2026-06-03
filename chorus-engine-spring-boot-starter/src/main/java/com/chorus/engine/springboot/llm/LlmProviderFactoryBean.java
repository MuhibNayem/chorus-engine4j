package com.chorus.engine.springboot.llm;

import com.chorus.engine.llm.LlmClient;
import com.chorus.engine.llm.provider.ProviderRegistry;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.BeanFactory;
import org.springframework.beans.factory.BeanFactoryAware;
import org.springframework.beans.factory.FactoryBean;

public final class LlmProviderFactoryBean implements FactoryBean<LlmClient>, BeanFactoryAware {

    private @Nullable BeanFactory beanFactory;
    private @NonNull String name = "";
    private @NonNull String type = "";
    private @NonNull String baseUrl = "";
    private @NonNull String apiKey = "";
    private @NonNull String apiKeyProperty = "";

    public void setName(@NonNull String name) { this.name = name; }
    public void setType(@NonNull String type) { this.type = type; }
    public void setBaseUrl(@NonNull String baseUrl) { this.baseUrl = baseUrl; }
    public void setApiKey(@NonNull String apiKey) { this.apiKey = apiKey; }
    public void setApiKeyProperty(@NonNull String apiKeyProperty) { this.apiKeyProperty = apiKeyProperty; }

    @Override
    public void setBeanFactory(@NonNull BeanFactory beanFactory) throws BeansException {
        this.beanFactory = beanFactory;
    }

    @Override
    public @NonNull LlmClient getObject() {
        if (beanFactory == null) {
            throw new IllegalStateException("BeanFactory has not been set");
        }
        ProviderRegistry registry = beanFactory.getBean(
            "chorusProviderRegistry", ProviderRegistry.class);
        String key = resolveApiKey();

        boolean alreadyRegistered = registry.hasProvider(name);

        switch (type) {
            case "openai" -> {
                if (!alreadyRegistered) {
                    registry.registerOpenAi(name, baseUrl, key, null);
                }
            }
            case "anthropic" -> {
                if (!alreadyRegistered) {
                    registry.registerAnthropic(name, key);
                }
            }
            case "gemini" -> {
                if (!alreadyRegistered) {
                    registry.registerGemini(name, key);
                }
            }
            case "vllm" -> {
                if (!alreadyRegistered) {
                    registry.registerVllm(name, baseUrl, key);
                }
            }
            case "mistral" -> {
                if (!alreadyRegistered) {
                    registry.registerMistral(name, key);
                }
            }
            case "deepseek" -> {
                if (!alreadyRegistered) {
                    registry.registerDeepSeek(name, key);
                }
            }
            case "groq" -> {
                if (!alreadyRegistered) {
                    registry.registerGroq(name, key);
                }
            }
            case "together" -> {
                if (!alreadyRegistered) {
                    registry.registerTogetherAi(name, key);
                }
            }
            case "fireworks" -> {
                if (!alreadyRegistered) {
                    registry.registerFireworks(name, key);
                }
            }
            case "cohere" -> {
                if (!alreadyRegistered) {
                    registry.registerCohere(name, key);
                }
            }
            case "mock" -> {
                if (!alreadyRegistered) {
                    registry.registerMockDefault(name, key.isEmpty() ? "Ready to help" : key);
                }
            }
            default -> throw new IllegalArgumentException(
                "Unsupported LLM provider type: " + type);
        }
        return registry.get(name);
    }

    @Override
    public @NonNull Class<?> getObjectType() {
        return LlmClient.class;
    }

    @Override
    public boolean isSingleton() {
        return true;
    }

    private @NonNull String resolveApiKey() {
        if (!apiKey.isEmpty()) {
            return apiKey;
        }
        if (!apiKeyProperty.isEmpty()) {
            String fromSys = System.getProperty(apiKeyProperty);
            if (fromSys != null && !fromSys.isEmpty()) {
                return fromSys;
            }
            String fromEnv = System.getenv(apiKeyProperty);
            if (fromEnv != null && !fromEnv.isEmpty()) {
                return fromEnv;
            }
        }
        return "";
    }
}
