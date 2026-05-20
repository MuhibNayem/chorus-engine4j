package com.chorus.engine.springboot.telemetry;

import com.chorus.engine.annotation.EventHandler;
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

public class EventHandlerAnnotationAotProcessor implements BeanFactoryInitializationAotProcessor {

    @Override
    public BeanFactoryInitializationAotContribution processAheadOfTime(ConfigurableListableBeanFactory beanFactory) {
        boolean hasHandler = false;
        for (String beanName : beanFactory.getBeanDefinitionNames()) {
            BeanDefinition bd = beanFactory.getBeanDefinition(beanName);
            Class<?> beanClass = resolveBeanClass(bd, beanFactory);
            if (beanClass == null) continue;
            for (Method m : beanClass.getDeclaredMethods()) {
                if (AnnotationUtils.findAnnotation(m, EventHandler.class) != null) {
                    hasHandler = true; break;
                }
            }
            if (hasHandler) break;
        }
        if (!hasHandler) return null;
        return new EventHandlerAnnotationAotContribution(beanFactory);
    }

    private static Class<?> resolveBeanClass(BeanDefinition bd, ConfigurableListableBeanFactory bf) {
        String className = bd.getBeanClassName();
        if (className == null) return null;
        try { return Class.forName(className, true, bf.getBeanClassLoader()); }
        catch (ClassNotFoundException e) { return null; }
    }

    private static final class EventHandlerAnnotationAotContribution implements BeanFactoryInitializationAotContribution {
        private final ConfigurableListableBeanFactory beanFactory;
        EventHandlerAnnotationAotContribution(ConfigurableListableBeanFactory beanFactory) { this.beanFactory = beanFactory; }

        @Override
        public void applyTo(org.springframework.aot.generate.GenerationContext ctx, BeanFactoryInitializationCode code) {
            RuntimeHints hints = ctx.getRuntimeHints();
            for (String beanName : beanFactory.getBeanDefinitionNames()) {
                BeanDefinition bd = beanFactory.getBeanDefinition(beanName);
                Class<?> beanClass = resolveBeanClass(bd, beanFactory);
                if (beanClass == null) continue;
                boolean hasHandler = false;
                for (Method m : beanClass.getDeclaredMethods()) {
                    if (AnnotationUtils.findAnnotation(m, EventHandler.class) != null) {
                        hasHandler = true;
                        hints.reflection().registerMethod(m, ExecutableMode.INVOKE);
                    }
                }
                if (hasHandler) {
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
