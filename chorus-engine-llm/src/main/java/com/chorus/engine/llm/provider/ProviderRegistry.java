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

    public void registerCustom(@NonNull String name, @NonNull LlmClient client) {
        providers.put(name, client);
    }

    public static @NonNull LlmClient anthropic(@NonNull String apiKey) {
        HttpClient client = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(30))
            .version(HttpClient.Version.HTTP_2)
            .build();
        return new AnthropicProvider(apiKey, client, Duration.ofSeconds(120), RetryPolicy.DEFAULT, new ObjectMapper(), CircuitBreaker.defaults());
    }

    public static @NonNull LlmClient gemini(@NonNull String apiKey) {
        HttpClient client = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(30))
            .version(HttpClient.Version.HTTP_2)
            .build();
        return new GeminiProvider(apiKey, client, Duration.ofSeconds(120), RetryPolicy.DEFAULT, new ObjectMapper(), CircuitBreaker.defaults());
    }

    public static @NonNull LlmClient vllm(@NonNull String baseUrl, @Nullable String apiKey) {
        HttpClient client = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(30))
            .version(HttpClient.Version.HTTP_2)
            .build();
        return new VllmChatProvider(baseUrl, apiKey, client, Duration.ofSeconds(120), RetryPolicy.DEFAULT, new ObjectMapper(), CircuitBreaker.defaults());
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
