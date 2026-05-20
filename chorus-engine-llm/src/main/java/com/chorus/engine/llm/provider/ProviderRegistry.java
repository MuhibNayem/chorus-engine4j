package com.chorus.engine.llm.provider;

import com.chorus.engine.llm.LlmClient;
import com.chorus.engine.llm.retry.CircuitBreaker;
import com.chorus.engine.llm.retry.RetryPolicy;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

import java.net.http.HttpClient;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;

/**
 * Registry of configured LLM providers. Thread-safe.
 * Supports runtime addition/removal of providers for multi-tenant deployments.
 */
public final class ProviderRegistry implements AutoCloseable {

    private final Map<String, LlmClient> providers = new ConcurrentHashMap<>();
    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;
    private final RetryPolicy defaultRetryPolicy;
    private final CircuitBreaker defaultCircuitBreaker;

    public ProviderRegistry(
        @NonNull HttpClient httpClient,
        @NonNull ObjectMapper objectMapper,
        @NonNull RetryPolicy defaultRetryPolicy,
        @NonNull CircuitBreaker defaultCircuitBreaker
    ) {
        this.httpClient = httpClient;
        this.objectMapper = objectMapper;
        this.defaultRetryPolicy = defaultRetryPolicy;
        this.defaultCircuitBreaker = defaultCircuitBreaker;
    }

    public static @NonNull ProviderRegistry defaults(@NonNull ObjectMapper objectMapper) {
        HttpClient client = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(30))
            .version(HttpClient.Version.HTTP_2)
            .build();
        return new ProviderRegistry(client, objectMapper, RetryPolicy.DEFAULT, CircuitBreaker.defaults());
    }

    public void registerOpenAi(
        @NonNull String name,
        @NonNull String baseUrl,
        @NonNull String apiKey,
        @Nullable String organization
    ) {
        providers.put(name, new OpenAiProvider(
            name, baseUrl, apiKey, organization,
            httpClient, objectMapper,
            defaultRetryPolicy,
            defaultCircuitBreaker,
            Executors.newVirtualThreadPerTaskExecutor()
        ));
    }

    public void registerAnthropic(@NonNull String name, @NonNull String apiKey) {
        providers.put(name, new AnthropicProvider(
            apiKey, httpClient, Duration.ofSeconds(120), defaultRetryPolicy, objectMapper, defaultCircuitBreaker
        ));
    }

    public void registerGemini(@NonNull String name, @NonNull String apiKey) {
        providers.put(name, new GeminiProvider(
            apiKey, httpClient, Duration.ofSeconds(120), defaultRetryPolicy, objectMapper, defaultCircuitBreaker
        ));
    }

    public void registerVllm(@NonNull String name, @NonNull String baseUrl, @Nullable String apiKey) {
        providers.put(name, new VllmChatProvider(
            baseUrl, apiKey, httpClient, Duration.ofSeconds(120), defaultRetryPolicy, objectMapper, defaultCircuitBreaker
        ));
    }

    /**
     * Registers a {@link MockLlmClient} — a zero-config, scriptable local LLM provider
     * for development and integration testing. No API key or network access required.
     *
     * @param name    provider alias (e.g. {@code "mock"})
     * @param scripts ordered response scripts; first-match wins, {@code "*"} is wildcard
     */
    public void registerMock(
        @NonNull String name,
        java.util.@NonNull List<MockLlmClient.ResponseScript> scripts
    ) {
        providers.put(name, new MockLlmClient(scripts, java.time.Duration.ofMillis(20)));
    }

    /**
     * Registers a {@link MockLlmClient} with a single wildcard script returning the given text.
     * Convenience method for the simplest zero-config local setup.
     *
     * @param name     provider alias (e.g. {@code "mock"})
     * @param response fixed response text
     */
    public void registerMockDefault(@NonNull String name, @NonNull String response) {
        providers.put(name, new MockLlmClient(
            java.util.List.of(MockLlmClient.ResponseScript.text(response)),
            java.time.Duration.ofMillis(20)
        ));
    }

    /**
     * Registers an Azure OpenAI provider.
     *
     * @param name             logical alias (e.g. {@code "gpt4o"})
     * @param resourceEndpoint {@code https://{resource}.openai.azure.com}
     * @param deploymentId     Azure deployment name (e.g. {@code "gpt-4o"})
     * @param apiVersion       Azure REST API version (e.g. {@code "2024-10-21"})
     * @param apiKey           Azure OpenAI API key
     */
    public void registerAzureOpenAi(
        @NonNull String name,
        @NonNull String resourceEndpoint,
        @NonNull String deploymentId,
        @NonNull String apiVersion,
        @NonNull String apiKey
    ) {
        providers.put(name, new AzureOpenAiProvider(
            name, resourceEndpoint, deploymentId, apiVersion, apiKey,
            httpClient, objectMapper, defaultRetryPolicy, defaultCircuitBreaker,
            Executors.newVirtualThreadPerTaskExecutor()
        ));
    }

    /**
     * Registers a Cohere provider (Command R / R+).
     *
     * @param name   logical alias (e.g. {@code "cohere"})
     * @param apiKey Cohere API key
     */
    public void registerCohere(@NonNull String name, @NonNull String apiKey) {
        providers.put(name, new CohereProvider(
            name, apiKey, httpClient, objectMapper, defaultRetryPolicy, defaultCircuitBreaker,
            Executors.newVirtualThreadPerTaskExecutor()
        ));
    }

    /**
     * Registers an AWS Bedrock provider (Converse API).
     * Supports all Bedrock-hosted models: Anthropic Claude, Llama 3, Mistral, Titan, etc.
     *
     * @param name          logical alias (e.g. {@code "bedrock-claude"})
     * @param region        AWS region (e.g. {@code "us-east-1"})
     * @param accessKeyId   AWS access key ID
     * @param secretKey     AWS secret access key
     * @param sessionToken  STS session token, or {@code null} for long-term credentials
     */
    public void registerBedrock(
        @NonNull String name,
        @NonNull String region,
        @NonNull String accessKeyId,
        @NonNull String secretKey,
        @Nullable String sessionToken
    ) {
        providers.put(name, new BedrockProvider(
            name, region, accessKeyId, secretKey, sessionToken,
            httpClient, objectMapper, defaultRetryPolicy, defaultCircuitBreaker,
            Executors.newVirtualThreadPerTaskExecutor()
        ));
    }

    /**
     * Convenience registration for Mistral AI (OpenAI-compatible endpoint).
     *
     * @param name   logical alias (e.g. {@code "mistral"})
     * @param apiKey Mistral API key
     */
    public void registerMistral(@NonNull String name, @NonNull String apiKey) {
        registerOpenAi(name, "https://api.mistral.ai", apiKey, null);
    }

    /**
     * Convenience registration for Together AI (OpenAI-compatible endpoint).
     *
     * @param name   logical alias (e.g. {@code "together"})
     * @param apiKey Together AI API key
     */
    public void registerTogetherAi(@NonNull String name, @NonNull String apiKey) {
        registerOpenAi(name, "https://api.together.xyz", apiKey, null);
    }

    /**
     * Convenience registration for DeepSeek (OpenAI-compatible endpoint).
     *
     * @param name   logical alias (e.g. {@code "deepseek"})
     * @param apiKey DeepSeek API key
     */
    public void registerDeepSeek(@NonNull String name, @NonNull String apiKey) {
        registerOpenAi(name, "https://api.deepseek.com", apiKey, null);
    }

    /**
     * Convenience registration for Groq (OpenAI-compatible endpoint).
     *
     * @param name   logical alias (e.g. {@code "groq"})
     * @param apiKey Groq API key
     */
    public void registerGroq(@NonNull String name, @NonNull String apiKey) {
        registerOpenAi(name, "https://api.groq.com/openai", apiKey, null);
    }

    /**
     * Convenience registration for Fireworks AI (OpenAI-compatible endpoint).
     *
     * @param name   logical alias (e.g. {@code "fireworks"})
     * @param apiKey Fireworks AI API key
     */
    public void registerFireworks(@NonNull String name, @NonNull String apiKey) {
        registerOpenAi(name, "https://api.fireworks.ai/inference", apiKey, null);
    }

    public void registerCustom(@NonNull String name, @NonNull LlmClient client) {
        providers.put(name, client);
    }

    public static @NonNull LlmClient anthropic(@NonNull String apiKey, @NonNull ObjectMapper objectMapper) {
        HttpClient client = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(30))
            .version(HttpClient.Version.HTTP_2)
            .build();
        return new AnthropicProvider(apiKey, client, Duration.ofSeconds(120), RetryPolicy.DEFAULT, objectMapper, CircuitBreaker.defaults());
    }

    public static @NonNull LlmClient gemini(@NonNull String apiKey, @NonNull ObjectMapper objectMapper) {
        HttpClient client = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(30))
            .version(HttpClient.Version.HTTP_2)
            .build();
        return new GeminiProvider(apiKey, client, Duration.ofSeconds(120), RetryPolicy.DEFAULT, objectMapper, CircuitBreaker.defaults());
    }

    public static @NonNull LlmClient vllm(@NonNull String baseUrl, @Nullable String apiKey, @NonNull ObjectMapper objectMapper) {
        HttpClient client = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(30))
            .version(HttpClient.Version.HTTP_2)
            .build();
        return new VllmChatProvider(baseUrl, apiKey, client, Duration.ofSeconds(120), RetryPolicy.DEFAULT, objectMapper, CircuitBreaker.defaults());
    }

    public @NonNull LlmClient get(@NonNull String name) {
        LlmClient client = providers.get(name);
        if (client == null) {
            throw new IllegalArgumentException("Unknown provider: " + name + ". Registered: " + providers.keySet());
        }
        return client;
    }

    public @Nullable LlmClient getOrNull(@NonNull String name) {
        return providers.get(name);
    }

    public boolean hasProvider(@NonNull String name) {
        return providers.containsKey(name);
    }

    public void remove(@NonNull String name) {
        LlmClient removed = providers.remove(name);
        if (removed instanceof AutoCloseable ac) {
            try { ac.close(); } catch (Exception ignored) {}
        }
    }

    public @NonNull Map<String, LlmClient> all() {
        return Map.copyOf(providers);
    }

    @Override
    public void close() {
        for (LlmClient client : providers.values()) {
            if (client instanceof AutoCloseable ac) {
                try { ac.close(); } catch (Exception ignored) {}
            }
        }
        providers.clear();
    }
}
