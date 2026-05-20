package com.chorus.engine.springboot.telemetry;

import com.chorus.engine.annotation.EventHandler;
import com.chorus.engine.telemetry.event.EventBus;
import org.jspecify.annotations.NonNull;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.beans.factory.support.BeanDefinitionRegistry;
import org.springframework.beans.factory.support.BeanDefinitionRegistryPostProcessor;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.AnnotationUtils;
import org.springframework.core.annotation.Order;

import java.lang.reflect.Method;
import java.util.Arrays;

/**
 * Scans for {@link EventHandler} methods on Spring beans and auto-subscribes
 * them to the {@link EventBus}.
 */
@Order(Ordered.LOWEST_PRECEDENCE - 40)
public class EventHandlerAnnotationProcessor implements BeanDefinitionRegistryPostProcessor {

    @Override
    public void postProcessBeanDefinitionRegistry(@NonNull BeanDefinitionRegistry registry) throws BeansException {
        // no-op
    }

    @Override
    public void postProcessBeanFactory(@NonNull ConfigurableListableBeanFactory beanFactory) throws BeansException {
        if (!beanFactory.containsBean("eventBus")) return;

        EventBus eventBus = beanFactory.getBean("eventBus", EventBus.class);

        for (String beanName : beanFactory.getBeanDefinitionNames()) {
            Object bean = beanFactory.getBean(beanName);
            Class<?> beanClass = bean.getClass();
            for (Method method : beanClass.getDeclaredMethods()) {
                EventHandler ann = AnnotationUtils.findAnnotation(method, EventHandler.class);
                if (ann == null) continue;

                method.setAccessible(true);
                String[] eventTypes = ann.value();
                if (eventTypes.length == 0) {
                    eventBus.subscribe("*", event -> {
                        try {
                            method.invoke(bean, event);
                        } catch (Exception e) {
                            throw new RuntimeException("Event handler failed", e);
                        }
                    });
                } else {
                    for (String type : eventTypes) {
                        eventBus.subscribe(type, event -> {
                            try {
                                method.invoke(bean, event);
                            } catch (Exception e) {
                                throw new RuntimeException("Event handler failed", e);
                            }
                        });
                    }
                }
            }
        }
    }
}
