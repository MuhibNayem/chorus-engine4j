package com.chorus.engine.springboot.swarm;

import org.jspecify.annotations.NonNull;

import java.util.List;

/**
 * Immutable metadata for a single swarm agent, registered as a Spring bean by
 * {@link SwarmAnnotationProcessor}.
 *
 * <p>All fields use AOT-serializable types (String, double, List&lt;String&gt;) so that
 * Spring AOT can generate the constructor-arg code when compiling to a GraalVM native image.
 * This is why the record is a top-level class rather than a nested member of
 * {@link SwarmOrchestratorFactoryBean} — Spring's {@code ValueCodeGenerator} does not
 * support code generation for inner/nested custom types.
 */
public record AgentMetadata(
    @NonNull String name,
    @NonNull String instructions,
    @NonNull String model,
    double temperature,
    @NonNull List<String> handoffTargets,
    @NonNull List<String> toolNames,
    @NonNull String beanName
) {}
