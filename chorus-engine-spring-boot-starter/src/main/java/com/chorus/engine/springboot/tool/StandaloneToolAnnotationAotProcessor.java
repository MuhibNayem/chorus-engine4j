package com.chorus.engine.springboot.tool;

import com.chorus.engine.annotation.ChorusTool;
import org.springframework.aot.hint.MemberCategory;
import org.springframework.aot.hint.RuntimeHints;
import org.springframework.beans.factory.aot.BeanFactoryInitializationAotContribution;
import org.springframework.beans.factory.aot.BeanFactoryInitializationAotProcessor;
import org.springframework.beans.factory.aot.BeanFactoryInitializationCode;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.core.annotation.AnnotationUtils;

public class StandaloneToolAnnotationAotProcessor implements BeanFactoryInitializationAotProcessor {

    @Override
    public BeanFactoryInitializationAotContribution processAheadOfTime(ConfigurableListableBeanFactory beanFactory) {
        boolean hasTool = false;
        for (String beanName : beanFactory.getBeanDefinitionNames()) {
            BeanDefinition bd = beanFactory.getBeanDefinition(beanName);
            Class<?> beanClass = resolveBeanClass(bd, beanFactory);
            if (beanClass != null && AnnotationUtils.findAnnotation(beanClass, ChorusTool.class) != null) {
                hasTool = true; break;
            }
        }
        if (!hasTool) return null;
        return new StandaloneToolAnnotationAotContribution(beanFactory);
    }

    private static Class<?> resolveBeanClass(BeanDefinition bd, ConfigurableListableBeanFactory bf) {
        String className = bd.getBeanClassName();
        if (className == null) return null;
        try { return Class.forName(className, true, bf.getBeanClassLoader()); }
        catch (ClassNotFoundException e) { return null; }
    }

    private static final class StandaloneToolAnnotationAotContribution implements BeanFactoryInitializationAotContribution {
        private final ConfigurableListableBeanFactory beanFactory;
        StandaloneToolAnnotationAotContribution(ConfigurableListableBeanFactory beanFactory) { this.beanFactory = beanFactory; }

        @Override
        public void applyTo(org.springframework.aot.generate.GenerationContext ctx, BeanFactoryInitializationCode code) {
            RuntimeHints hints = ctx.getRuntimeHints();
            for (String beanName : beanFactory.getBeanDefinitionNames()) {
                BeanDefinition bd = beanFactory.getBeanDefinition(beanName);
                Class<?> beanClass = resolveBeanClass(bd, beanFactory);
                if (beanClass == null) continue;
                if (AnnotationUtils.findAnnotation(beanClass, ChorusTool.class) == null) continue;
                hints.reflection().registerType(beanClass, MemberCategory.INVOKE_PUBLIC_METHODS);
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
