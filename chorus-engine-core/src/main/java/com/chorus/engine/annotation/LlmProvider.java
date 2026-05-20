package com.chorus.engine.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Declares a bean method as an LLM provider registration.
 *
 * <p>The framework calls the appropriate
 * {@code ProviderRegistry.register*(...)} method based on {@code type()}.
 *
 * <p>Either {@code apiKey} or {@code apiKeyProperty} must be provided.
 * If {@code apiKeyProperty} is set, the value is read from system properties
 * or environment variables.
 *
 * <p>Example:
 * <pre>{@code
 * @LlmProvider(name = "claude", type = "anthropic", apiKeyProperty = "CLAUDE_API_KEY")
 * @Bean
 * public Void registerClaude(ProviderRegistry registry) {
 *     registry.registerAnthropic("claude", null, System.getProperty("CLAUDE_API_KEY"), null);
 *     return null;
 * }
 * }</pre>
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface LlmProvider {
    /** Provider identifier. */
    String name();

    /** Provider type: openai | anthropic | gemini | vllm | custom. */
    String type();

    /** Base URL (optional). */
    String baseUrl() default "";

    /** API key (inline — use with caution). */
    String apiKey() default "";

    /** System property or environment variable name containing the API key. */
    String apiKeyProperty() default "";
}
