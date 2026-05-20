package com.chorus.engine.llm.provider;

import com.chorus.engine.llm.LlmClient;
import com.chorus.engine.llm.retry.CircuitBreaker;
import com.chorus.engine.llm.retry.RetryPolicy;
import com.chorus.engine.llm.testutil.FakeHttpClient;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.*;

class ProviderRegistryTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private ProviderRegistry registry;
    private FakeHttpClient fakeClient;

    @BeforeEach
    void setUp() {
        fakeClient = new FakeHttpClient();
        registry = new ProviderRegistry(fakeClient, MAPPER, RetryPolicy.DEFAULT, CircuitBreaker.defaults());
    }

    @Test
    void registerOpenAi_and_get_returns_correct_client() {
        registry.registerOpenAi("openai", "http://localhost:8080", "key", null);
        LlmClient client = registry.get("openai");
        assertThat(client).isInstanceOf(OpenAiProvider.class);
        assertThat(client.providerName()).isEqualTo("openai");
    }

    @Test
    void registerAnthropic_and_get_returns_correct_client() {
        registry.registerAnthropic("anthropic", "key");
        LlmClient client = registry.get("anthropic");
        assertThat(client).isInstanceOf(AnthropicProvider.class);
        assertThat(client.providerName()).isEqualTo("anthropic");
    }

    @Test
    void registerGemini_and_get_returns_correct_client() {
        registry.registerGemini("gemini", "key");
        LlmClient client = registry.get("gemini");
        assertThat(client).isInstanceOf(GeminiProvider.class);
        assertThat(client.providerName()).isEqualTo("gemini");
    }

    @Test
    void registerVllm_and_get_returns_correct_client() {
        registry.registerVllm("vllm", "http://localhost:8000", null);
        LlmClient client = registry.get("vllm");
        assertThat(client).isInstanceOf(VllmChatProvider.class);
        assertThat(client.providerName()).isEqualTo("vllm");
    }

    @Test
    void staticAnthropic_acceptsObjectMapper() {
        LlmClient client = ProviderRegistry.anthropic("key", MAPPER);
        assertThat(client).isInstanceOf(AnthropicProvider.class);
    }

    @Test
    void staticGemini_acceptsObjectMapper() {
        LlmClient client = ProviderRegistry.gemini("key", MAPPER);
        assertThat(client).isInstanceOf(GeminiProvider.class);
    }

    @Test
    void staticVllm_acceptsObjectMapper() {
        LlmClient client = ProviderRegistry.vllm("http://localhost:8000", null, MAPPER);
        assertThat(client).isInstanceOf(VllmChatProvider.class);
    }

    @Test
    void getOrNull_returns_null_for_missing() {
        assertThat(registry.getOrNull("missing")).isNull();
    }

    @Test
    void hasProvider_returns_false_for_missing() {
        assertThat(registry.hasProvider("missing")).isFalse();
    }

    @Test
    void hasProvider_returns_true_for_registered() {
        registry.registerOpenAi("o", "http://localhost", "k", null);
        assertThat(registry.hasProvider("o")).isTrue();
    }

    @Test
    void remove_removes_provider() {
        registry.registerOpenAi("o", "http://localhost", "k", null);
        registry.remove("o");
        assertThat(registry.hasProvider("o")).isFalse();
        assertThat(registry.getOrNull("o")).isNull();
    }

    @Test
    void duplicate_registration_overwrites() {
        registry.registerOpenAi("o", "http://localhost:1", "k1", null);
        registry.registerOpenAi("o", "http://localhost:2", "k2", null);
        LlmClient client = registry.get("o");
        assertThat(client).isInstanceOf(OpenAiProvider.class);
    }

    @Test
    void get_non_existent_throws() {
        assertThatThrownBy(() -> registry.get("missing"))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("Unknown provider");
    }

    @Test
    void all_returns_copy() {
        registry.registerOpenAi("o", "http://localhost", "k", null);
        assertThat(registry.all()).containsKey("o");
    }

    // ---- Null rejection (ConcurrentHashMap does not permit null keys) ----

    @Test
    void registerOpenAi_null_name_throws() {
        assertThatThrownBy(() -> registry.registerOpenAi(null, "http://localhost", "k", null))
            .isInstanceOf(NullPointerException.class);
    }

    @Test
    void registerAnthropic_null_name_throws() {
        assertThatThrownBy(() -> registry.registerAnthropic(null, "k"))
            .isInstanceOf(NullPointerException.class);
    }

    @Test
    void registerGemini_null_name_throws() {
        assertThatThrownBy(() -> registry.registerGemini(null, "k"))
            .isInstanceOf(NullPointerException.class);
    }

    @Test
    void registerVllm_null_name_throws() {
        assertThatThrownBy(() -> registry.registerVllm(null, "http://localhost", null))
            .isInstanceOf(NullPointerException.class);
    }

    @Test
    void get_null_name_throws() {
        assertThatThrownBy(() -> registry.get(null))
            .isInstanceOf(NullPointerException.class);
    }

    @Test
    void getOrNull_null_name_throws() {
        assertThatThrownBy(() -> registry.getOrNull(null))
            .isInstanceOf(NullPointerException.class);
    }

    @Test
    void hasProvider_null_name_throws() {
        assertThatThrownBy(() -> registry.hasProvider(null))
            .isInstanceOf(NullPointerException.class);
    }

    @Test
    void remove_null_name_throws() {
        assertThatThrownBy(() -> registry.remove(null))
            .isInstanceOf(NullPointerException.class);
    }
}
