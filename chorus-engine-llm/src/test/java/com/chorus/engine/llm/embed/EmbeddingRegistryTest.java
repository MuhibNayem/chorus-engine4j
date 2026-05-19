package com.chorus.engine.llm.embed;

import com.chorus.engine.core.result.Result;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.*;

class EmbeddingRegistryTest {

    private EmbeddingRegistry registry;

    @BeforeEach
    void setUp() {
        registry = new EmbeddingRegistry();
    }

    @Test
    void register_and_get() {
        EmbeddingClient client = new FakeEmbeddingClient("openai");
        registry.register("text-embedding-3-small", client);
        assertThat(registry.get("text-embedding-3-small")).isSameAs(client);
    }

    @Test
    void get_returns_null_for_missing() {
        assertThat(registry.get("missing")).isNull();
    }

    @Test
    void getOrThrow_returns_client() {
        EmbeddingClient client = new FakeEmbeddingClient("openai");
        registry.register("model", client);
        assertThat(registry.getOrThrow("model")).isSameAs(client);
    }

    @Test
    void getOrThrow_throws_for_missing() {
        assertThatThrownBy(() -> registry.getOrThrow("missing"))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("No embedding client registered");
    }

    @Test
    void has_returns_true_when_registered() {
        registry.register("model", new FakeEmbeddingClient("openai"));
        assertThat(registry.has("model")).isTrue();
    }

    @Test
    void has_returns_false_when_missing() {
        assertThat(registry.has("model")).isFalse();
    }

    @Test
    void remove_removes_client() {
        registry.register("model", new FakeEmbeddingClient("openai"));
        registry.remove("model");
        assertThat(registry.has("model")).isFalse();
    }

    @Test
    void all_returns_copy() {
        registry.register("m1", new FakeEmbeddingClient("a"));
        assertThat(registry.all()).containsKey("m1");
    }

    @Test
    void duplicate_registration_overwrites() {
        EmbeddingClient first = new FakeEmbeddingClient("a");
        EmbeddingClient second = new FakeEmbeddingClient("b");
        registry.register("model", first);
        registry.register("model", second);
        assertThat(registry.get("model")).isSameAs(second);
    }

    // ---- Null rejection via ConcurrentHashMap ----

    @Test
    void register_null_modelName_throws() {
        assertThatThrownBy(() -> registry.register(null, new FakeEmbeddingClient("a")))
            .isInstanceOf(NullPointerException.class);
    }

    @Test
    void get_null_modelName_throws() {
        assertThatThrownBy(() -> registry.get(null))
            .isInstanceOf(NullPointerException.class);
    }

    @Test
    void getOrThrow_null_modelName_throws() {
        assertThatThrownBy(() -> registry.getOrThrow(null))
            .isInstanceOf(NullPointerException.class);
    }

    @Test
    void has_null_modelName_throws() {
        assertThatThrownBy(() -> registry.has(null))
            .isInstanceOf(NullPointerException.class);
    }

    @Test
    void remove_null_modelName_throws() {
        assertThatThrownBy(() -> registry.remove(null))
            .isInstanceOf(NullPointerException.class);
    }

    private static class FakeEmbeddingClient implements EmbeddingClient {
        private final String name;

        FakeEmbeddingClient(String name) {
            this.name = name;
        }

        @Override
        public Result<float[], EmbeddingError> embed(String text, EmbedOptions options) {
            return null;
        }

        @Override
        public Result<List<float[]>, EmbeddingError> embedBatch(List<String> texts, EmbedOptions options) {
            return null;
        }

        @Override
        public String providerName() {
            return name;
        }

        @Override
        public String modelName() {
            return "fake";
        }

        @Override
        public int nativeDimensions() {
            return 3;
        }

        @Override
        public boolean isLocal() {
            return false;
        }

        @Override
        public HealthStatus health() {
            return HealthStatus.HEALTHY;
        }
    }
}
