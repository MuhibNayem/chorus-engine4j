package com.chorus.engine.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Configures the vector store implementation.
 *
 * <p>Place on a {@code DataSource} bean or a configuration class.
 *
 * <p>Example:
 * <pre>{@code
 * @VectorStore(type = "pgvector", config = {"table=chunks", "dimensions=768"})
 * @Bean
 * public DataSource dataSource() { ... }
 * }</pre>
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
public @interface VectorStore {
    /** Store type: memory | pgvector | redis | milvus | pinecone. */
    String type();

    /** Type-specific configuration key-value pairs. */
    String[] config() default {};
}
