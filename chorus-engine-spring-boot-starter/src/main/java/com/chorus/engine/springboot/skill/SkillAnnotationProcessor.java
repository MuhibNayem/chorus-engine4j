package com.chorus.engine.springboot.skill;
import org.springframework.core.ResolvableType;

import com.chorus.engine.annotation.Skill;
import com.chorus.engine.annotation.SkillSource;
import com.chorus.engine.skills.SkillLoader;
import com.chorus.engine.skills.SkillRegistry;
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

import java.nio.file.Path;
import java.util.List;

/**
 * Scans for {@link Skill} classes and {@link SkillSource} annotations,
 * loads skill definitions, and registers them in {@link SkillRegistry}.
 */
@Order(Ordered.LOWEST_PRECEDENCE - 50)
public class SkillAnnotationProcessor implements BeanDefinitionRegistryPostProcessor, SmartInitializingSingleton {

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
        if (beanFactory == null || !beanFactory.containsBean("skillRegistry")) return;

        SkillRegistry registry = beanFactory.getBean("skillRegistry", SkillRegistry.class);

        // Register @Skill classes
        for (String beanName : beanFactory.getBeanDefinitionNames()) {
            BeanDefinition bd = beanFactory.getBeanDefinition(beanName);
            Class<?> beanClass = resolveBeanClass(bd, beanFactory);
            if (beanClass == null) continue;

            Skill ann = AnnotationUtils.findAnnotation(beanClass, Skill.class);
            if (ann == null) continue;

            com.chorus.engine.skills.Skill skill = new com.chorus.engine.skills.Skill(
                ann.id(),
                ann.name().isEmpty() ? ann.id() : ann.name(),
                ann.description(),
                ann.systemPrompt(),
                List.of(ann.toolNames()),
                java.util.Map.of(),
                List.of(ann.tags())
            );
            registry.register(skill);
        }

        // Load @SkillSource directories
        for (String beanName : beanFactory.getBeanDefinitionNames()) {
            BeanDefinition bd = beanFactory.getBeanDefinition(beanName);
            Class<?> beanClass = resolveBeanClass(bd, beanFactory);
            if (beanClass == null) continue;

            SkillSource source = AnnotationUtils.findAnnotation(beanClass, SkillSource.class);
            if (source == null) continue;

            SkillLoader loader = new SkillLoader();
            for (String location : source.value()) {
                if (location.startsWith("classpath:")) {
                    try {
                        com.chorus.engine.skills.Skill skill = loader.loadFromClasspath(
                            location.substring("classpath:".length()));
                        registry.register(skill);
                    } catch (Exception e) {
                        // Skip invalid skill files
                    }
                } else if (location.startsWith("file:")) {
                    try {
                        List<com.chorus.engine.skills.Skill> skills = loader.loadFromDirectory(
                            Path.of(location.substring("file:".length())));
                        skills.forEach(registry::register);
                    } catch (Exception e) {
                        // Skip invalid directories
                    }
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
