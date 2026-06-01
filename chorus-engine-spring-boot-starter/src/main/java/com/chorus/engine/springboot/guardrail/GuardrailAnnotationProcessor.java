package com.chorus.engine.springboot.guardrail;
import org.springframework.core.ResolvableType;

import com.chorus.engine.annotation.Guardrail;
import com.chorus.engine.guardrails.TieredGuardrailEngine;
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

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Collects all {@link Guardrail}-annotated beans, sorts them by tier,
 * and registers them.
 */
@Order(Ordered.LOWEST_PRECEDENCE - 60)
public class GuardrailAnnotationProcessor implements BeanDefinitionRegistryPostProcessor, SmartInitializingSingleton {

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
        if (beanFactory == null || !beanFactory.containsBean("tieredGuardrailEngine")) return;

        List<com.chorus.engine.guardrails.Guardrail> guardrails = new ArrayList<>();

        for (String beanName : beanFactory.getBeanDefinitionNames()) {
            BeanDefinition bd = beanFactory.getBeanDefinition(beanName);
            Class<?> beanClass = resolveBeanClass(bd, beanFactory);
            if (beanClass == null) continue;

            Guardrail ann = AnnotationUtils.findAnnotation(beanClass, Guardrail.class);
            if (ann == null) continue;

            if (com.chorus.engine.guardrails.Guardrail.class.isAssignableFrom(beanClass)) {
                try {
                    com.chorus.engine.guardrails.Guardrail gr =
                        beanFactory.getBean(beanName, com.chorus.engine.guardrails.Guardrail.class);
                    guardrails.add(gr);
                } catch (Exception e) {
                    // Defensive
                }
            }
        }

        guardrails.sort(Comparator.comparingInt(g -> {
            Guardrail ann = AnnotationUtils.findAnnotation(g.getClass(), Guardrail.class);
            return ann != null ? ann.tier() : Integer.MAX_VALUE;
        }));

        if (!guardrails.isEmpty()) {
            TieredGuardrailEngine engine = beanFactory.getBean("tieredGuardrailEngine",
                TieredGuardrailEngine.class);
            engine.register(guardrails);
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
