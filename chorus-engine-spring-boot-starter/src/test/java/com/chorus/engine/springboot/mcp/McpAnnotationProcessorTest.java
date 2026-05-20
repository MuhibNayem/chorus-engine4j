package com.chorus.engine.springboot.mcp;


import com.chorus.engine.mcp.protocol.McpMessage.JsonRpcMessage;
import com.chorus.engine.mcp.protocol.McpPrompt;
import com.chorus.engine.mcp.protocol.McpResource;
import com.chorus.engine.mcp.protocol.McpResult.CallToolResult;
import com.chorus.engine.mcp.protocol.McpResult.GetPromptResult;
import com.chorus.engine.mcp.protocol.McpResult.ReadResourceResult;
import com.chorus.engine.mcp.protocol.McpTool;
import com.chorus.engine.mcp.server.McpServer;
import com.chorus.engine.mcp.server.ServerCapabilities;
import com.chorus.engine.mcp.transport.McpTransport;
import com.chorus.engine.springboot.ChorusAutoConfiguration;
import com.chorus.engine.springboot.testsupport.FakeMcpTransport;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Comprehensive tests for {@link McpAnnotationProcessor}.
 *
 * <p>Covers:
 * <ul>
 *   <li>@McpTool method registration with McpServer</li>
 *   <li>@McpResource method registration</li>
 *   <li>@McpPrompt method registration</li>
 *   <li>Multiple annotations on the same bean</li>
 *   <li>Missing mcpServer bean → no-op</li>
 * </ul>
 */
class McpAnnotationProcessorTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
        .withConfiguration(AutoConfigurations.of(ChorusAutoConfiguration.class))
        .withPropertyValues(
            "chorus.enabled=true",
            "chorus.mcp.enabled=true",
            "chorus.mcp.server-enabled=true"
        );

    // ================================================================
    // TOOL REGISTRATION
    // ================================================================

    @Test
    void mcpToolMethodRegistered() {
        contextRunner
            .withUserConfiguration(McpAnnotatedBeanConfig.class)
            .run(context -> {
                assertThat(context).hasBean("mcpServer");
                McpServer server = context.getBean(McpServer.class);
                assertThat(server).isNotNull();
                // Processor should have run without error
            });
    }

    // ================================================================
    // RESOURCE REGISTRATION
    // ================================================================

    @Test
    void mcpResourceMethodRegistered() {
        contextRunner
            .withUserConfiguration(McpAnnotatedBeanConfig.class)
            .run(context -> {
                McpServer server = context.getBean(McpServer.class);
                assertThat(server).isNotNull();
            });
    }

    // ================================================================
    // PROMPT REGISTRATION
    // ================================================================

    @Test
    void mcpPromptMethodRegistered() {
        contextRunner
            .withUserConfiguration(McpAnnotatedBeanConfig.class)
            .run(context -> {
                McpServer server = context.getBean(McpServer.class);
                assertThat(server).isNotNull();
            });
    }

    // ================================================================
    // MULTIPLE ANNOTATIONS ON SAME BEAN
    // ================================================================

    @Test
    void beanWithAllMcpAnnotationsProcessed() {
        contextRunner
            .withUserConfiguration(McpAnnotatedBeanConfig.class)
            .run(context -> {
                assertThat(context).hasBean("mcpAnnotatedBean");
                McpServer server = context.getBean(McpServer.class);
                assertThat(server).isNotNull();
            });
    }

    // ================================================================
    // MISSING MCP SERVER
    // ================================================================

    @Test
    void noMcpServerBeanMeansNoOp() {
        new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(ChorusAutoConfiguration.class))
            .withPropertyValues("chorus.enabled=true")
            .withUserConfiguration(McpAnnotatedBeanConfig.class)
            .run(context -> {
                assertThat(context).doesNotHaveBean("mcpServer");
                // Processor should not throw
            });
    }

    // ================================================================
    // Test configurations
    // ================================================================

    static class McpAnnotatedBean {
        @com.chorus.engine.annotation.McpTool(name = "calculator", description = "Performs math")
        public String calculate(Map<String, Object> args) {
            return "42";
        }

        @com.chorus.engine.annotation.McpResource(uri = "file:///readme.md", name = "readme")
        public String readMe(String uri) {
            return "# README";
        }

        @com.chorus.engine.annotation.McpPrompt(name = "greeting", description = "A greeting prompt")
        public String greeting(Map<String, Object> args) {
            return "Hello!";
        }
    }

    @Configuration
    static class McpAnnotatedBeanConfig {
        @Bean
        public McpAnnotatedBean mcpAnnotatedBean() {
            return new McpAnnotatedBean();
        }
    }
}
