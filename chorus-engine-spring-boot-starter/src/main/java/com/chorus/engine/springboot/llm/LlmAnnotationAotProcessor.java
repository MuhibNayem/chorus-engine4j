package com.chorus.engine.springboot.llm;

import com.chorus.engine.annotation.LlmProvider;
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

public class LlmAnnotationAotProcessor implements BeanFactoryInitializationAotProcessor {

    @Override
    public BeanFactoryInitializationAotContribution processAheadOfTime(ConfigurableListableBeanFactory beanFactory) {
        boolean hasLlm = false;
        for (String beanName : beanFactory.getBeanDefinitionNames()) {
            BeanDefinition bd = beanFactory.getBeanDefinition(beanName);
            Class<?> beanClass = resolveBeanClass(bd, beanFactory);
            if (beanClass == null) continue;
            for (Method m : beanClass.getDeclaredMethods()) {
                if (AnnotationUtils.findAnnotation(m, LlmProvider.class) != null) {
                    hasLlm = true; break;
                }
            }
            if (hasLlm) break;
        }
        if (!hasLlm) return null;
        return new LlmAnnotationAotContribution(beanFactory);
    }

    private static Class<?> resolveBeanClass(BeanDefinition bd, ConfigurableListableBeanFactory bf) {
        String className = bd.getBeanClassName();
        if (className == null) return null;
        try { return Class.forName(className, true, bf.getBeanClassLoader()); }
        catch (ClassNotFoundException e) { return null; }
    }

    private static final class LlmAnnotationAotContribution implements BeanFactoryInitializationAotContribution {
        private final ConfigurableListableBeanFactory beanFactory;
        LlmAnnotationAotContribution(ConfigurableListableBeanFactory beanFactory) { this.beanFactory = beanFactory; }

        @Override
        public void applyTo(org.springframework.aot.generate.GenerationContext ctx, BeanFactoryInitializationCode code) {
            RuntimeHints hints = ctx.getRuntimeHints();
            hints.reflection().registerType(LlmProviderFactoryBean.class, MemberCategory.INVOKE_PUBLIC_CONSTRUCTORS);
            for (String beanName : beanFactory.getBeanDefinitionNames()) {
                BeanDefinition bd = beanFactory.getBeanDefinition(beanName);
                Class<?> beanClass = resolveBeanClass(bd, beanFactory);
                if (beanClass == null) continue;
                boolean hasLlm = false;
                for (Method m : beanClass.getDeclaredMethods()) {
                    if (AnnotationUtils.findAnnotation(m, LlmProvider.class) != null) {
                        hasLlm = true;
                        hints.reflection().registerMethod(m, ExecutableMode.INVOKE);
                    }
                }
                if (hasLlm) {
                    hints.reflection().registerType(beanClass, MemberCategory.INVOKE_PUBLIC_METHODS);
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
