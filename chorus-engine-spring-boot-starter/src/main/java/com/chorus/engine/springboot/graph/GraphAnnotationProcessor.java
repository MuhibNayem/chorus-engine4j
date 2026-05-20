package com.chorus.engine.springboot.graph;

import org.springframework.core.ResolvableType;
import com.chorus.engine.annotation.GraphWorkflow;
import com.chorus.engine.graph.state.CompiledGraph;
import org.jspecify.annotations.NonNull;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.beans.factory.support.BeanDefinitionBuilder;
import org.springframework.beans.factory.support.BeanDefinitionRegistry;
import org.springframework.beans.factory.support.BeanDefinitionRegistryPostProcessor;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.AnnotationUtils;
import org.springframework.core.annotation.Order;

/**
 * Scans for {@link GraphWorkflow} classes, and registers a CompiledGraph bean via GraphWorkflowFactoryBean.
 */
@Order(Ordered.LOWEST_PRECEDENCE - 80)
public class GraphAnnotationProcessor implements BeanDefinitionRegistryPostProcessor {

    @Override
    public void postProcessBeanDefinitionRegistry(@NonNull BeanDefinitionRegistry registry) throws BeansException {
        if (!(registry instanceof ConfigurableListableBeanFactory beanFactory)) {
            return;
        }
        for (String beanName : registry.getBeanDefinitionNames()) {
            BeanDefinition bd = registry.getBeanDefinition(beanName);
            Class<?> beanClass = resolveBeanClass(bd, beanFactory);
            if (beanClass == null) continue;

            GraphWorkflow wf = AnnotationUtils.findAnnotation(beanClass, GraphWorkflow.class);
            if (wf == null) continue;

            String compiledGraphBeanName = beanName + "_compiledGraph";
            if (registry.containsBeanDefinition(compiledGraphBeanName)) continue;

            BeanDefinitionBuilder builder = BeanDefinitionBuilder.rootBeanDefinition(GraphWorkflowFactoryBean.class)
                .addPropertyValue("workflowBeanName", beanName)
                .addPropertyValue("workflowClass", beanClass)
                .addPropertyValue("entryPoint", wf.entryPoint())
                .addPropertyValue("finishPoints", wf.finishPoints());

            registry.registerBeanDefinition(compiledGraphBeanName, builder.getBeanDefinition());
        }
    }

    @Override
    public void postProcessBeanFactory(@NonNull ConfigurableListableBeanFactory beanFactory) throws BeansException {
        // no-op
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
