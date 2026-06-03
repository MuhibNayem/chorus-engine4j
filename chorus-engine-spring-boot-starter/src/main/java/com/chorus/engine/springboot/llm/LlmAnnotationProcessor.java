package com.chorus.engine.springboot.llm;

import com.chorus.engine.annotation.LlmProvider;
import com.chorus.engine.annotation.PrimaryLlmProvider;
import org.jspecify.annotations.NonNull;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.beans.factory.support.BeanDefinitionBuilder;
import org.springframework.beans.factory.support.BeanDefinitionRegistry;
import org.springframework.beans.factory.support.BeanDefinitionRegistryPostProcessor;
import org.springframework.core.Ordered;
import org.springframework.core.ResolvableType;
import org.springframework.core.annotation.AnnotationUtils;
import org.springframework.core.annotation.Order;
import org.springframework.util.StringUtils;

import java.lang.reflect.Method;

/**
 * Scans for {@link LlmProvider}-annotated methods on Spring beans and registers
 * a {@link LlmProviderFactoryBean} for each declared provider.
 *
 * <p>Also detects {@link PrimaryLlmProvider} and promotes the matching factory
 * bean to the primary {@code "llmClient"} Spring bean name.
 *
 * <p>All resolution (API keys, {@code ProviderRegistry}) is deferred to
 * {@link LlmProviderFactoryBean#getObject()}, avoiding early instantiation of
 * configuration-properties beans.
 */
@Order(Ordered.LOWEST_PRECEDENCE - 85)
public class LlmAnnotationProcessor implements BeanDefinitionRegistryPostProcessor {

    @Override
    public void postProcessBeanDefinitionRegistry(@NonNull BeanDefinitionRegistry registry) throws BeansException {
        if (!(registry instanceof ConfigurableListableBeanFactory beanFactory)) {
            return;
        }

        String primaryName = findPrimaryProviderName(beanFactory);

        for (String beanName : beanFactory.getBeanDefinitionNames()) {
            BeanDefinition bd = beanFactory.getBeanDefinition(beanName);
            Class<?> beanClass = resolveBeanClass(bd, beanFactory);
            if (beanClass == null) continue;

            for (Method method : beanClass.getDeclaredMethods()) {
                LlmProvider ann = AnnotationUtils.findAnnotation(method, LlmProvider.class);
                if (ann == null) continue;

                String providerBeanName = "llmProvider_" + ann.name();
                if (registry.containsBeanDefinition(providerBeanName)) continue;

                BeanDefinitionBuilder builder = BeanDefinitionBuilder
                    .rootBeanDefinition(LlmProviderFactoryBean.class)
                    .addPropertyValue("name", ann.name())
                    .addPropertyValue("type", ann.type())
                    .addPropertyValue("baseUrl", ann.baseUrl())
                    .addPropertyValue("apiKey", ann.apiKey())
                    .addPropertyValue("apiKeyProperty", ann.apiKeyProperty());

                if (ann.name().equals(primaryName)) {
                    builder.setPrimary(true);
                }

                registry.registerBeanDefinition(providerBeanName, builder.getBeanDefinition());
            }
        }

        if (primaryName != null) {
            String providerBeanName = "llmProvider_" + primaryName;
            if (registry.containsBeanDefinition(providerBeanName)
                && !registry.containsBeanDefinition("llmClient")) {
                registry.registerAlias(providerBeanName, "llmClient");
            }
        }
    }

    @Override
    public void postProcessBeanFactory(@NonNull ConfigurableListableBeanFactory beanFactory) throws BeansException {
    }

    private String findPrimaryProviderName(ConfigurableListableBeanFactory beanFactory) {
        for (String beanName : beanFactory.getBeanDefinitionNames()) {
            BeanDefinition bd = beanFactory.getBeanDefinition(beanName);
            Class<?> beanClass = resolveBeanClass(bd, beanFactory);
            if (beanClass == null) continue;

            PrimaryLlmProvider ann = AnnotationUtils.findAnnotation(beanClass, PrimaryLlmProvider.class);
            if (ann != null) {
                return ann.value();
            }
        }
        return null;
    }

    private Class<?> resolveBeanClass(BeanDefinition bd, ConfigurableListableBeanFactory beanFactory) {
        String className = bd.getBeanClassName();
        if (className != null) {
            try {
                return Class.forName(className, true, beanFactory.getBeanClassLoader());
            } catch (ClassNotFoundException e) {
                return null;
            }
        }
        ResolvableType resolvableType = bd.getResolvableType();
        if (resolvableType != ResolvableType.NONE) {
            return resolvableType.resolve();
        }
        return null;
    }
}
