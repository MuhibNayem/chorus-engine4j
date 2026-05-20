package com.chorus.engine.springboot.skill;

import com.chorus.engine.annotation.Skill;
import com.chorus.engine.annotation.SkillSource;
import org.springframework.aot.hint.MemberCategory;
import org.springframework.aot.hint.RuntimeHints;
import org.springframework.beans.factory.aot.BeanFactoryInitializationAotContribution;
import org.springframework.beans.factory.aot.BeanFactoryInitializationAotProcessor;
import org.springframework.beans.factory.aot.BeanFactoryInitializationCode;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.core.annotation.AnnotationUtils;

public class SkillAnnotationAotProcessor implements BeanFactoryInitializationAotProcessor {

    @Override
    public BeanFactoryInitializationAotContribution processAheadOfTime(ConfigurableListableBeanFactory beanFactory) {
        boolean hasSkill = false;
        for (String beanName : beanFactory.getBeanDefinitionNames()) {
            BeanDefinition bd = beanFactory.getBeanDefinition(beanName);
            Class<?> beanClass = resolveBeanClass(bd, beanFactory);
            if (beanClass != null && (AnnotationUtils.findAnnotation(beanClass, Skill.class) != null
                || AnnotationUtils.findAnnotation(beanClass, SkillSource.class) != null)) {
                hasSkill = true; break;
            }
        }
        if (!hasSkill) return null;
        return new SkillAnnotationAotContribution(beanFactory);
    }

    private static Class<?> resolveBeanClass(BeanDefinition bd, ConfigurableListableBeanFactory bf) {
        String className = bd.getBeanClassName();
        if (className == null) return null;
        try { return Class.forName(className, true, bf.getBeanClassLoader()); }
        catch (ClassNotFoundException e) { return null; }
    }

    private static final class SkillAnnotationAotContribution implements BeanFactoryInitializationAotContribution {
        private final ConfigurableListableBeanFactory beanFactory;
        SkillAnnotationAotContribution(ConfigurableListableBeanFactory beanFactory) { this.beanFactory = beanFactory; }

        @Override
        public void applyTo(org.springframework.aot.generate.GenerationContext ctx, BeanFactoryInitializationCode code) {
            RuntimeHints hints = ctx.getRuntimeHints();
            for (String beanName : beanFactory.getBeanDefinitionNames()) {
                BeanDefinition bd = beanFactory.getBeanDefinition(beanName);
                Class<?> beanClass = resolveBeanClass(bd, beanFactory);
                if (beanClass == null) continue;
                if (AnnotationUtils.findAnnotation(beanClass, Skill.class) != null
                    || AnnotationUtils.findAnnotation(beanClass, SkillSource.class) != null) {
                    hints.reflection().registerType(beanClass, MemberCategory.INVOKE_PUBLIC_METHODS);
                }
            }
            // Register skill JSON resource patterns
            for (String beanName : beanFactory.getBeanDefinitionNames()) {
                BeanDefinition bd = beanFactory.getBeanDefinition(beanName);
                Class<?> beanClass = resolveBeanClass(bd, beanFactory);
                if (beanClass == null) continue;
                SkillSource source = AnnotationUtils.findAnnotation(beanClass, SkillSource.class);
                if (source != null) {
                    for (String loc : source.value()) {
                        if (loc.startsWith("classpath:")) {
                            hints.resources().registerPattern(loc.substring("classpath:".length()) + "**/*.json");
                        }
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
