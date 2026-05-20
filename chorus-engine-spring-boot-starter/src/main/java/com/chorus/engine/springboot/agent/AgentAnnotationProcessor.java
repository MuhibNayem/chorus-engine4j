package com.chorus.engine.springboot.agent;
import org.springframework.core.ResolvableType;

import com.chorus.engine.agent.loop.AgentLoop;
import com.chorus.engine.annotation.Agent;
import com.chorus.engine.annotation.Tool;
import com.chorus.engine.springboot.tool.AnnotatedMethodTool;
import com.chorus.engine.springboot.tool.JsonSchemaGenerator;
import com.chorus.engine.springboot.tool.ParamBinding;
import com.chorus.engine.tools.ToolRegistry;
import org.jspecify.annotations.NonNull;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.beans.factory.support.BeanDefinitionBuilder;
import org.springframework.beans.factory.support.BeanDefinitionRegistry;
import org.springframework.beans.factory.support.BeanDefinitionRegistryPostProcessor;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.AnnotationUtils;
import org.springframework.core.annotation.Order;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Scans the bean factory for classes annotated with {@link Agent}, discovers
 * {@link Tool} methods, and registers:
 * <ul>
 *   <li>One {@link AnnotatedMethodTool} bean per {@code @Tool} method</li>
 *   <li>One {@link AgentLoop} bean per {@code @Agent} class (via
 *       {@link AgentLoopFactoryBean} to defer {@code ChorusProperties} lookup)</li>
 *   <li>Auto-registration of tool beans into {@link ToolRegistry}</li>
 * </ul>
 *
 * <p>The processor is idempotent — it skips beans that are already registered.
 * It runs both at runtime and during Spring AOT processing; in native images
 * the bean definitions it creates are restored from AOT-generated code.
 */
@Order(Ordered.LOWEST_PRECEDENCE - 100)
public class AgentAnnotationProcessor implements BeanDefinitionRegistryPostProcessor, SmartInitializingSingleton {

    @Override
    public void postProcessBeanDefinitionRegistry(@NonNull BeanDefinitionRegistry registry) throws BeansException {
        if (!(registry instanceof ConfigurableListableBeanFactory beanFactory)) {
            return;
        }

        try {
            String[] beanNames = beanFactory.getBeanDefinitionNames();
            for (String beanName : beanNames) {
                BeanDefinition bd = beanFactory.getBeanDefinition(beanName);
                Class<?> beanClass = resolveBeanClass(bd, beanFactory);
                if (beanClass == null) continue;

                Agent agentAnn = AnnotationUtils.findAnnotation(beanClass, Agent.class);
                if (agentAnn == null) continue;

                processAgent(beanName, beanClass, agentAnn, registry);
            }
        } catch (Exception e) {
            // Defensive: don't let annotation processing break the context
            // Log would be ideal but we don't have a logger here
        }
    }

    private ConfigurableListableBeanFactory beanFactory;

    @Override
    public void postProcessBeanFactory(@NonNull ConfigurableListableBeanFactory beanFactory) throws BeansException {
        this.beanFactory = beanFactory;
    }

    @Override
    public void afterSingletonsInstantiated() {
        if (beanFactory == null || !beanFactory.containsBean("toolRegistry")) return;

        try {
            ToolRegistry toolRegistry = beanFactory.getBean("toolRegistry", ToolRegistry.class);
            String[] beanNames = beanFactory.getBeanDefinitionNames();
            for (String beanName : beanNames) {
                BeanDefinition bd = beanFactory.getBeanDefinition(beanName);
                Class<?> beanClass = resolveBeanClass(bd, beanFactory);
                if (beanClass == null) continue;

                if (AnnotationUtils.findAnnotation(beanClass, Agent.class) == null) continue;

                List<Method> toolMethods = findToolMethods(beanClass);
                for (int i = 0; i < toolMethods.size(); i++) {
                    String toolBeanName = beanName + "_tool_" + i;
                    if (beanFactory.containsBean(toolBeanName)) {
                        com.chorus.engine.tools.Tool tool = beanFactory.getBean(toolBeanName, com.chorus.engine.tools.Tool.class);
                        toolRegistry.register(tool);
                    }
                }
            }
        } catch (Exception e) {
            // Defensive: don't let annotation processing break the context
        }
    }

    private void processAgent(String agentBeanName, Class<?> agentClass, Agent agentAnn,
                              BeanDefinitionRegistry registry) {
        String agentName = agentAnn.name().isEmpty() ? agentBeanName : agentAnn.name();
        String agentLoopBeanName = agentName + "_agentLoop";

        // Idempotency check
        if (registry.containsBeanDefinition(agentLoopBeanName)) {
            return;
        }

        // Discover @Tool methods
        List<Method> toolMethods = findToolMethods(agentClass);

        for (int i = 0; i < toolMethods.size(); i++) {
            Method method = toolMethods.get(i);
            Tool toolAnn = AnnotationUtils.findAnnotation(method, Tool.class);
            String toolName = (toolAnn != null && !toolAnn.value().isEmpty())
                ? toolAnn.value()
                : method.getName();
            String toolDesc = toolAnn != null ? toolAnn.description() : "";

            List<ParamBinding> bindings = AnnotatedMethodTool.buildParamBindings(method);
            Map<String, Object> schema = JsonSchemaGenerator.forParameters(bindings);
            List<String> paramTypeNames = AnnotatedMethodTool.buildParamTypeNames(method);

            String toolBeanName = agentBeanName + "_tool_" + i;

            if (!registry.containsBeanDefinition(toolBeanName)) {
                BeanDefinitionBuilder builder = BeanDefinitionBuilder
                    .rootBeanDefinition(AnnotatedMethodTool.class)
                    .addConstructorArgValue(toolName)
                    .addConstructorArgValue(toolDesc)
                    .addConstructorArgValue(schema)
                    .addConstructorArgReference(agentBeanName)
                    .addConstructorArgValue(method.getName())
                    .addConstructorArgValue(paramTypeNames)
                    .addConstructorArgValue(bindings);
                registry.registerBeanDefinition(toolBeanName, builder.getBeanDefinition());
            }
        }

        // Register AgentLoop via FactoryBean so that ChorusProperties lookup
        // is deferred to singleton-instantiation time.
        BeanDefinitionBuilder factoryBuilder = BeanDefinitionBuilder
            .rootBeanDefinition(AgentLoopFactoryBean.class)
            .addPropertyValue("agentName", agentName)
            .addPropertyValue("systemPrompt", agentAnn.systemPrompt())
            .addPropertyValue("agentBeanName", agentBeanName)
            .addPropertyValue("model", agentAnn.model())
            .addPropertyValue("temperature", agentAnn.temperature())
            .addPropertyValue("maxTokens", agentAnn.maxTokens())
            .addPropertyValue("maxRounds", agentAnn.maxRounds());

        registry.registerBeanDefinition(agentLoopBeanName, factoryBuilder.getBeanDefinition());
    }

    private @NonNull List<Method> findToolMethods(@NonNull Class<?> clazz) {
        List<Method> tools = new ArrayList<>();
        for (Method method : clazz.getDeclaredMethods()) {
            if (AnnotationUtils.findAnnotation(method, Tool.class) != null) {
                tools.add(method);
            }
        }
        return tools;
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
