package com.chorus.engine.springboot.swarm;
import org.springframework.core.ResolvableType;

import com.chorus.engine.annotation.SwarmAgent;
import org.jspecify.annotations.NonNull;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.beans.factory.support.BeanDefinitionBuilder;
import org.springframework.beans.factory.support.BeanDefinitionRegistry;
import org.springframework.beans.factory.support.BeanDefinitionRegistryPostProcessor;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.AnnotationUtils;
import org.springframework.core.annotation.Order;

import java.util.Arrays;
import java.util.List;

/**
 * Scans for {@link SwarmAgent} beans and registers a
 * {@link SwarmOrchestratorFactoryBean} that defers {@code ChorusProperties}
 * lookup and {@code Tool} resolution to singleton-instantiation time.
 *
 * <p>This processor never calls {@code getBean()} during
 * {@code postProcessBeanDefinitionRegistry}, avoiding early instantiation of
 * {@code @ConfigurationProperties} beans.
 */
@Order(Ordered.LOWEST_PRECEDENCE - 90)
public class SwarmAnnotationProcessor implements BeanDefinitionRegistryPostProcessor {

    @Override
    public void postProcessBeanDefinitionRegistry(@NonNull BeanDefinitionRegistry registry) throws BeansException {
        if (!(registry instanceof ConfigurableListableBeanFactory beanFactory)) {
            return;
        }

        List<AgentMetadata> agentMetadata = new java.util.ArrayList<>();

        for (String beanName : beanFactory.getBeanDefinitionNames()) {
            BeanDefinition bd = beanFactory.getBeanDefinition(beanName);
            Class<?> beanClass = resolveBeanClass(bd, beanFactory);
            if (beanClass == null) continue;

            SwarmAgent ann = AnnotationUtils.findAnnotation(beanClass, SwarmAgent.class);
            if (ann == null) continue;

            List<String> handoffTargets = Arrays.asList(ann.handoffTargets());
            List<String> toolNames = Arrays.asList(ann.toolNames());

            agentMetadata.add(new AgentMetadata(
                ann.name(),
                ann.instructions(),
                ann.model(),
                ann.temperature(),
                handoffTargets,
                toolNames,
                beanName
            ));
        }

        if (agentMetadata.isEmpty()) {
            return;
        }

        // Register each AgentMetadata as an individual Spring bean with constructor args.
        // All arg types (String, double, List<String>) are natively serializable by Spring AOT's
        // ValueCodeGenerator — unlike List<AgentMetadata> which is an unsupported custom type.
        List<String> registeredBeanNames = new java.util.ArrayList<>();
        for (AgentMetadata meta : agentMetadata) {
            String metaBeanName = "swarmAgentMeta." + meta.name();
            BeanDefinitionBuilder metaBuilder = BeanDefinitionBuilder
                .genericBeanDefinition(AgentMetadata.class)
                .addConstructorArgValue(meta.name())
                .addConstructorArgValue(meta.instructions())
                .addConstructorArgValue(meta.model())
                .addConstructorArgValue(meta.temperature())
                .addConstructorArgValue(meta.handoffTargets())
                .addConstructorArgValue(meta.toolNames())
                .addConstructorArgValue(meta.beanName());
            if (registry.containsBeanDefinition(metaBeanName)) {
                registry.removeBeanDefinition(metaBeanName);
            }
            registry.registerBeanDefinition(metaBeanName, metaBuilder.getBeanDefinition());
            registeredBeanNames.add(metaBeanName);
        }

        // Store the agent bean names (List<String>) as the property value on the factory bean.
        // List<String> is AOT-serializable; the factory bean resolves agents at getObject()
        // time via direct O(1) getBean(name) lookups — no full-registry scan.
        BeanDefinitionBuilder factoryBuilder = BeanDefinitionBuilder
            .rootBeanDefinition(SwarmOrchestratorFactoryBean.class)
            .addPropertyValue("agentBeanNames", registeredBeanNames);

        String orchestratorBeanName = "swarmOrchestrator";
        if (registry.containsBeanDefinition(orchestratorBeanName)) {
            registry.removeBeanDefinition(orchestratorBeanName);
        }
        registry.registerBeanDefinition(orchestratorBeanName, factoryBuilder.getBeanDefinition());
    }

    @Override
    public void postProcessBeanFactory(@NonNull ConfigurableListableBeanFactory beanFactory) throws BeansException {
        // All work is done in postProcessBeanDefinitionRegistry.
        // The FactoryBean defers ChorusProperties / Tool resolution to getObject().
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
