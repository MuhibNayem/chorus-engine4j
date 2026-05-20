package com.chorus.engine.springboot.agent;

import com.chorus.engine.annotation.Agent;
import com.chorus.engine.annotation.Tool;
import org.springframework.aot.hint.ExecutableMode;
import org.springframework.aot.hint.MemberCategory;
import org.springframework.aot.hint.RuntimeHints;
import org.springframework.beans.factory.aot.BeanFactoryInitializationAotContribution;
import org.springframework.beans.factory.aot.BeanFactoryInitializationAotProcessor;
import org.springframework.beans.factory.aot.BeanFactoryInitializationCode;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.core.annotation.AnnotationUtils;

import java.lang.reflect.Method;

/**
 * Spring AOT processor that registers reflection hints for all
 * {@link Agent}-annotated classes and their {@link Tool} methods.
 *
 * <p>This processor pairs with {@link AgentAnnotationProcessor}:
 * <ul>
 *   <li>{@code AgentAnnotationProcessor} registers bean definitions at runtime</li>
 *   <li>{@code AgentAnnotationAotProcessor} registers GraalVM reflection hints</li>
 * </ul>
 *
 * <p>Registered hints:
 * <ul>
 *   <li>{@code MemberCategory.INVOKE_PUBLIC_METHODS} on every {@code @Agent} class</li>
 *   <li>{@code ExecutableMode.INVOKE} on every {@code @Tool} method</li>
 * </ul>
 */
public class AgentAnnotationAotProcessor implements BeanFactoryInitializationAotProcessor {

    @Override
    public BeanFactoryInitializationAotContribution processAheadOfTime(
            ConfigurableListableBeanFactory beanFactory) {

        boolean hasAgents = false;
        for (String beanName : beanFactory.getBeanDefinitionNames()) {
            BeanDefinition bd = beanFactory.getBeanDefinition(beanName);
            Class<?> beanClass = resolveBeanClass(bd, beanFactory);
            if (beanClass != null && AnnotationUtils.findAnnotation(beanClass, Agent.class) != null) {
                hasAgents = true;
                break;
            }
        }

        if (!hasAgents) {
            return null;
        }

        return new AgentAnnotationAotContribution(beanFactory);
    }

    private static Class<?> resolveBeanClass(BeanDefinition bd, ConfigurableListableBeanFactory beanFactory) {
        String className = bd.getBeanClassName();
        if (className == null) return null;
        try {
            return Class.forName(className, true, beanFactory.getBeanClassLoader());
        } catch (ClassNotFoundException e) {
            return null;
        }
    }

    private static final class AgentAnnotationAotContribution implements BeanFactoryInitializationAotContribution {

        private final ConfigurableListableBeanFactory beanFactory;

        AgentAnnotationAotContribution(ConfigurableListableBeanFactory beanFactory) {
            this.beanFactory = beanFactory;
        }

        @Override
        public void applyTo(
                org.springframework.aot.generate.GenerationContext generationContext,
                BeanFactoryInitializationCode beanFactoryInitializationCode) {

            RuntimeHints hints = generationContext.getRuntimeHints();

            for (String beanName : beanFactory.getBeanDefinitionNames()) {
                BeanDefinition bd = beanFactory.getBeanDefinition(beanName);
                Class<?> beanClass = resolveBeanClass(bd, beanFactory);
                if (beanClass == null) continue;

                Agent agentAnn = AnnotationUtils.findAnnotation(beanClass, Agent.class);
                if (agentAnn == null) continue;

                // Register the agent class for reflection
                hints.reflection().registerType(beanClass, MemberCategory.INVOKE_PUBLIC_METHODS);

                // Register each @Tool method for invocation
                for (Method method : beanClass.getDeclaredMethods()) {
                    if (AnnotationUtils.findAnnotation(method, Tool.class) != null) {
                        hints.reflection().registerMethod(method, ExecutableMode.INVOKE);
                    }
                }
            }
        }

        private Class<?> resolveBeanClass(BeanDefinition bd, ConfigurableListableBeanFactory bf) {
            String className = bd.getBeanClassName();
            if (className == null) return null;
            try {
                return Class.forName(className, true, bf.getBeanClassLoader());
            } catch (ClassNotFoundException e) {
                return null;
            }
        }
    }
}
