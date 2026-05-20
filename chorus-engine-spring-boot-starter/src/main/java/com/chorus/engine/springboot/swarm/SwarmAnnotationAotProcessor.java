package com.chorus.engine.springboot.swarm;

import com.chorus.engine.annotation.SwarmAgent;
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

public class SwarmAnnotationAotProcessor implements BeanFactoryInitializationAotProcessor {

    @Override
    public BeanFactoryInitializationAotContribution processAheadOfTime(ConfigurableListableBeanFactory beanFactory) {
        boolean hasSwarm = false;
        for (String beanName : beanFactory.getBeanDefinitionNames()) {
            BeanDefinition bd = beanFactory.getBeanDefinition(beanName);
            Class<?> beanClass = resolveBeanClass(bd, beanFactory);
            if (beanClass != null && AnnotationUtils.findAnnotation(beanClass, SwarmAgent.class) != null) {
                hasSwarm = true;
                break;
            }
        }
        if (!hasSwarm) return null;
        return new SwarmAnnotationAotContribution(beanFactory);
    }

    private static Class<?> resolveBeanClass(BeanDefinition bd, ConfigurableListableBeanFactory bf) {
        String className = bd.getBeanClassName();
        if (className == null) return null;
        try {
            return Class.forName(className, true, bf.getBeanClassLoader());
        } catch (ClassNotFoundException e) {
            return null;
        }
    }

    private static final class SwarmAnnotationAotContribution implements BeanFactoryInitializationAotContribution {
        private final ConfigurableListableBeanFactory beanFactory;
        SwarmAnnotationAotContribution(ConfigurableListableBeanFactory beanFactory) { this.beanFactory = beanFactory; }

        @Override
        public void applyTo(org.springframework.aot.generate.GenerationContext ctx, BeanFactoryInitializationCode code) {
            RuntimeHints hints = ctx.getRuntimeHints();
            for (String beanName : beanFactory.getBeanDefinitionNames()) {
                BeanDefinition bd = beanFactory.getBeanDefinition(beanName);
                Class<?> beanClass = resolveBeanClass(bd, beanFactory);
                if (beanClass == null) continue;
                if (AnnotationUtils.findAnnotation(beanClass, SwarmAgent.class) == null) continue;
                hints.reflection().registerType(beanClass, MemberCategory.INVOKE_PUBLIC_METHODS);
                for (Method m : beanClass.getDeclaredMethods()) {
                    if (AnnotationUtils.findAnnotation(m, Tool.class) != null) {
                        hints.reflection().registerMethod(m, ExecutableMode.INVOKE);
                    }
                }
            }
        }

        private Class<?> resolveBeanClass(BeanDefinition bd, ConfigurableListableBeanFactory bf) {
            String className = bd.getBeanClassName();
            if (className == null) return null;
            try { return Class.forName(className, true, bf.getBeanClassLoader()); }
            catch (ClassNotFoundException e) { return null; }
        }
    }
}
