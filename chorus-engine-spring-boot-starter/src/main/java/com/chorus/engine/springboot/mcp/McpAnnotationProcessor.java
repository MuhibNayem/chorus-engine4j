package com.chorus.engine.springboot.mcp;

import com.chorus.engine.annotation.McpPrompt;
import com.chorus.engine.annotation.McpResource;
import com.chorus.engine.annotation.McpTool;
import com.chorus.engine.mcp.protocol.McpResult;
import com.chorus.engine.mcp.protocol.McpResult.CallToolResult;
import com.chorus.engine.mcp.protocol.McpResult.GetPromptResult;
import com.chorus.engine.mcp.protocol.McpResult.ReadResourceResult;
import com.chorus.engine.mcp.server.McpServer;
import org.jspecify.annotations.NonNull;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.beans.factory.support.BeanDefinitionRegistry;
import org.springframework.beans.factory.support.BeanDefinitionRegistryPostProcessor;
import org.springframework.core.Ordered;
import org.springframework.core.ResolvableType;
import org.springframework.core.annotation.AnnotationUtils;
import org.springframework.core.annotation.Order;

import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;

/**
 * Scans for {@link McpTool}, {@link McpResource}, and {@link McpPrompt}
 * methods on Spring beans and registers them with the {@link McpServer}.
 *
 * <p>Registration uses reflection-based dispatch: annotated methods are wrapped
 * in handler lambdas that invoke them at call time, so the bean instance is
 * always the fully-initialised Spring proxy.
 */
@Order(Ordered.LOWEST_PRECEDENCE - 70)
public class McpAnnotationProcessor
        implements BeanDefinitionRegistryPostProcessor, SmartInitializingSingleton {

    private ConfigurableListableBeanFactory beanFactory;

    @Override
    public void postProcessBeanDefinitionRegistry(@NonNull BeanDefinitionRegistry registry)
            throws BeansException {}

    @Override
    public void postProcessBeanFactory(@NonNull ConfigurableListableBeanFactory beanFactory)
            throws BeansException {
        this.beanFactory = beanFactory;
    }

    @Override
    public void afterSingletonsInstantiated() {
        if (beanFactory == null || !beanFactory.containsBean("mcpServer")) return;

        McpServer mcpServer = beanFactory.getBean("mcpServer", McpServer.class);

        for (String beanName : beanFactory.getBeanDefinitionNames()) {
            try {
                Object bean = beanFactory.getBean(beanName);
                for (Method method : bean.getClass().getDeclaredMethods()) {
                    McpTool tool = AnnotationUtils.findAnnotation(method, McpTool.class);
                    if (tool != null) registerTool(mcpServer, bean, method, tool);

                    McpResource resource = AnnotationUtils.findAnnotation(method, McpResource.class);
                    if (resource != null) registerResource(mcpServer, bean, method, resource);

                    McpPrompt prompt = AnnotationUtils.findAnnotation(method, McpPrompt.class);
                    if (prompt != null) registerPrompt(mcpServer, bean, method, prompt);
                }
            } catch (Exception ignored) {}
        }
    }

    private void registerTool(McpServer server, Object target, Method method, McpTool ann) {
        String name        = ann.name().isEmpty() ? method.getName() : ann.name();
        String description = ann.description();
        Map<String, Object> schema = Map.of("type", "object", "properties", Map.of(),
                                            "description", description);

        com.chorus.engine.mcp.protocol.McpTool mcpTool =
            new com.chorus.engine.mcp.protocol.McpTool(name, description, schema);

        method.setAccessible(true);
        server.registerTool(mcpTool, args -> {
            try {
                Object result = method.getParameterCount() == 0
                    ? method.invoke(target) : method.invoke(target, args);
                return CallToolResult.text(result != null ? result.toString() : "");
            } catch (Exception e) {
                Throwable cause = e.getCause() != null ? e.getCause() : e;
                return CallToolResult.error(cause.getMessage());
            }
        });
    }

    private void registerResource(McpServer server, Object target, Method method, McpResource ann) {
        String uri         = ann.uri();
        String name        = ann.name().isEmpty() ? method.getName() : ann.name();
        String description = ann.description();
        String mimeType    = ann.mimeType();

        com.chorus.engine.mcp.protocol.McpResource mcpResource =
            new com.chorus.engine.mcp.protocol.McpResource(uri, name, description, mimeType);

        method.setAccessible(true);
        server.registerResource(mcpResource, requestUri -> {
            try {
                Object result = method.getParameterCount() == 0
                    ? method.invoke(target) : method.invoke(target, requestUri);
                String content = result != null ? result.toString() : "";
                return new ReadResourceResult(
                    List.of(new McpResult.ResourceContent(requestUri, mimeType, content, null)));
            } catch (Exception e) {
                Throwable cause = e.getCause() != null ? e.getCause() : e;
                return new ReadResourceResult(
                    List.of(new McpResult.ResourceContent(requestUri, "text/plain",
                        "Error: " + cause.getMessage(), null)));
            }
        });
    }

    private void registerPrompt(McpServer server, Object target, Method method, McpPrompt ann) {
        String name        = ann.name().isEmpty() ? method.getName() : ann.name();
        String description = ann.description();

        com.chorus.engine.mcp.protocol.McpPrompt mcpPrompt =
            new com.chorus.engine.mcp.protocol.McpPrompt(name, description, List.of());

        method.setAccessible(true);
        server.registerPrompt(mcpPrompt, args -> {
            try {
                Object result = method.getParameterCount() == 0
                    ? method.invoke(target) : method.invoke(target, args);
                String text = result != null ? result.toString() : "";
                return new GetPromptResult(description,
                    List.of(new McpResult.PromptMessage("assistant",
                        new McpResult.Content.TextContent(text))));
            } catch (Exception e) {
                Throwable cause = e.getCause() != null ? e.getCause() : e;
                return new GetPromptResult(description,
                    List.of(new McpResult.PromptMessage("assistant",
                        new McpResult.Content.TextContent("Error: " + cause.getMessage()))));
            }
        });
    }

    private Class<?> resolveBeanClass(BeanDefinition bd, ConfigurableListableBeanFactory bf) {
        String className = bd.getBeanClassName();
        if (className != null) {
            try { return Class.forName(className, true, bf.getBeanClassLoader()); }
            catch (ClassNotFoundException e) { return null; }
        }
        ResolvableType rt = bd.getResolvableType();
        if (rt != ResolvableType.NONE) {
            Class<?> resolved = rt.resolve();
            if (resolved != null) return resolved;
        }
        return null;
    }
}
