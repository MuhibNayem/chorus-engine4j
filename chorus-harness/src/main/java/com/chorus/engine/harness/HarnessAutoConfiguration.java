package com.chorus.engine.harness;

import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;

/**
 * Spring Boot auto-configuration for the chorus-harness semantic routing and worker engine.
 */
@AutoConfiguration
@ConditionalOnClass(EmbeddingModel.class)
@ConditionalOnProperty(prefix = "chorus.harness", name = "enabled", havingValue = "true", matchIfMissing = true)
public class HarnessAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    public SemanticRouter semanticRouter(EmbeddingModel embeddingModel) {
        return new SemanticRouter(embeddingModel);
    }

    @Bean
    @ConditionalOnMissingBean
    public WorkerEngine workerEngine(SemanticRouter semanticRouter) {
        return new WorkerEngine(semanticRouter);
    }
}
