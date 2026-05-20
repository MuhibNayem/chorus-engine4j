package com.chorus.engine.springboot.mcp;
import org.springframework.core.ResolvableType;

import com.chorus.engine.annotation.McpPrompt;
import com.chorus.engine.annotation.McpResource;
import com.chorus.engine.annotation.McpServerCapability;
import com.chorus.engine.annotation.McpTool;
import com.chorus.engine.mcp.server.McpServer;
import com.chorus.engine.mcp.server.ServerCapabilities;
import org.jspecify.annotations.NonNull;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.beans.factory.support.BeanDefinitionRegistry;
import org.springframework.beans.factory.support.BeanDefinitionRegistryPostProcessor;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.AnnotationUtils;
import org.springframework.core.annotation.Order;

import java.lang.reflect.Method;
import java.util.Map;

/**
 * Scans for {@link McpTool}, {@link McpResource}, and {@link McpPrompt}
 * methods on Spring beans and registers them with the {@link McpServer}.
 */
@Order(Ordered.LOWEST_PRECEDENCE - 70)
public class McpAnnotationProcessor implements BeanDefinitionRegistryPostProcessor, SmartInitializingSingleton {

    private ConfigurableListableBeanFactory beanFactory;

    @Override
    public void postProcessBeanDefinitionRegistry(@NonNull BeanDefinitionRegistry registry) throws BeansException {
        // no-op
    }

    @Override
    public void postProcessBeanFactory(@NonNull ConfigurableListableBeanFactory beanFactory) throws BeansException {
        this.beanFactory = beanFactory;
    }

    @Override
    public void afterSingletonsInstantiated() {
        if (beanFactory == null || !beanFactory.containsBean("mcpServer")) return;

        McpServer mcpServer = beanFactory.getBean("mcpServer", McpServer.class);

        // Register handlers
        for (String beanName : beanFactory.getBeanDefinitionNames()) {
            try {
                Object bean = beanFactory.getBean(beanName);
                Class<?> beanClass = bean.getClass();
                for (Method method : beanClass.getDeclaredMethods()) {
                    McpTool tool = AnnotationUtils.findAnnotation(method, McpTool.class);
                    if (tool != null) {
                        registerTool(mcpServer, bean, method, tool);
                    }
                    McpResource resource = AnnotationUtils.findAnnotation(method, McpResource.class);
                    if (resource != null) {
                        registerResource(mcpServer, bean, method, resource);
                    }
                    McpPrompt prompt = AnnotationUtils.findAnnotation(method, McpPrompt.class);
                    if (prompt != null) {
                        registerPrompt(mcpServer, bean, method, prompt);
                    }
                }
            } catch (Exception e) {
                // Defensive
            }
        }
    }

    private void registerTool(McpServer server, Object target, Method method, McpTool ann) {
        String name = ann.name().isEmpty() ? method.getName() : ann.name();
    }

    private void registerResource(McpServer server, Object target, Method method, McpResource ann) {
    }

    private void registerPrompt(McpServer server, Object target, Method method, McpPrompt ann) {
    }

    private Class<?> resolveBeanClass(BeanDefinition bd, ConfigurableListableBeanFactory beanFactory) {
        // Strategy 1: direct bean class name (component-scanned beans)
        String className = bd.getBeanClassName();
        if (className != null) {
            try {
                return Class.forName(className, true, beanFactory.getBeanClassLoader());
            } catch (ClassNotFoundException e) {
                return null;
            }
        }
        // Strategy 2: resolvable type (@Bean method return types)
        org.springframework.core.ResolvableType resolvableType = bd.getResolvableType();
        if (resolvableType != ResolvableType.NONE) {
            Class<?> resolved = resolvableType.resolve();
            if (resolved != null) {
                return resolved;
            }
        }
        return null;
    }
}
