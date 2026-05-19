package com.chorus.engine.starter;

import com.chorus.engine.checkpoint.DurableCheckpointer;
import com.chorus.engine.checkpoint.JsonFileCheckpointer;
import com.chorus.engine.core.checkpoint.Checkpointer;
import com.chorus.engine.core.llm.ChorusChatModel;
import com.chorus.engine.core.llm.SpringAiChatModelAdapter;
import com.chorus.engine.core.memory.ChorusVectorStore;
import com.chorus.engine.core.memory.EmbeddingService;
import com.chorus.engine.tools.FilesystemTools;
import com.chorus.engine.tools.GitTools;
import com.chorus.engine.tools.ShellTool;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;

import java.nio.file.Path;

@AutoConfiguration
@EnableConfigurationProperties(ChorusProperties.class)
@ConditionalOnProperty(prefix = "chorus", name = "enabled", havingValue = "true", matchIfMissing = true)
public class ChorusAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    public Checkpointer checkpointer(ChorusProperties properties) {
        String mode = properties.getCheckpointMode();
        if ("sync".equals(mode) || "async".equals(mode) || "exit".equals(mode)) {
            return new DurableCheckpointer(DurableCheckpointer.DurabilityMode.valueOf(mode.toUpperCase()));
        }
        return new JsonFileCheckpointer(Path.of(properties.getCheckpointDir().replace("${user.home}", System.getProperty("user.home"))));
    }

    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnClass(ChatClient.class)
    public ChorusChatModel chorusChatModel(ChatClient chatClient) {
        return new SpringAiChatModelAdapter(chatClient);
    }

    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnClass(ChatModel.class)
    public ChatClient chatClient(ChatModel chatModel) {
        return ChatClient.create(chatModel);
    }

    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnClass(EmbeddingModel.class)
    public EmbeddingService embeddingService(EmbeddingModel embeddingModel) {
        return new EmbeddingService(embeddingModel);
    }

    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnClass({VectorStore.class, EmbeddingModel.class})
    public ChorusVectorStore chorusVectorStore(VectorStore vectorStore, EmbeddingModel embeddingModel,
                                                ChorusProperties properties) {
        return new ChorusVectorStore(vectorStore, embeddingModel,
            properties.getVectorStore().getSimilarityThreshold(),
            properties.getVectorStore().getTopK());
    }

    @Bean
    @ConditionalOnMissingBean
    public FilesystemTools filesystemTools() {
        return new FilesystemTools();
    }

    @Bean
    @ConditionalOnMissingBean
    public ShellTool shellTool() {
        return new ShellTool();
    }

    @Bean
    @ConditionalOnMissingBean
    public GitTools gitTools() {
        return new GitTools();
    }
}
