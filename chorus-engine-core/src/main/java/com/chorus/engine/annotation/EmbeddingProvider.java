package com.chorus.engine.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Declares a bean method as an embedding provider registration.
 *
 * <p>Example:
 * <pre>{@code
 * @EmbeddingProvider(name = "local-ollama", type = "ollama", baseUrl = "http://localhost:11434", model = "nomic-embed-text")
 * @Bean
 * public Void registerOllamaEmbedding(EmbeddingRegistry registry) { ... }
 * }</pre>
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface EmbeddingProvider {
    String name();
    String type(); // openai | ollama | custom
    String baseUrl() default "";
    String apiKey() default "";
    String apiKeyProperty() default "";
    String model() default "";
    int dimensions() default -1;
}
