package com.chorus.engine.springboot.tool;
import org.springframework.core.ResolvableType;

import com.chorus.engine.annotation.ChorusTool;
import com.chorus.engine.tools.Tool;
import com.chorus.engine.tools.ToolRegistry;
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

/**
 * Scans for classes implementing {@link Tool} that are also annotated with
 * {@link ChorusTool} and registers them in {@link ToolRegistry}.
 */
@Order(Ordered.LOWEST_PRECEDENCE - 30)
public class StandaloneToolAnnotationProcessor implements BeanDefinitionRegistryPostProcessor, SmartInitializingSingleton {

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
        if (beanFactory == null || !beanFactory.containsBean("toolRegistry")) return;

        ToolRegistry registry = beanFactory.getBean("toolRegistry", ToolRegistry.class);

        for (String beanName : beanFactory.getBeanDefinitionNames()) {
            BeanDefinition bd = beanFactory.getBeanDefinition(beanName);
            Class<?> beanClass = resolveBeanClass(bd, beanFactory);
            if (beanClass == null) continue;

            ChorusTool ann = AnnotationUtils.findAnnotation(beanClass, ChorusTool.class);
            if (ann == null) continue;

            if (Tool.class.isAssignableFrom(beanClass)) {
                try {
                    Tool tool = beanFactory.getBean(beanName, Tool.class);
                    registry.register(tool);
                } catch (Exception e) {
                    // Defensive
                }
            }
        }
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
