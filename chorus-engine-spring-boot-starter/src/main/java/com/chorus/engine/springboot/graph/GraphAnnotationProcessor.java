package com.chorus.engine.springboot.graph;

import org.springframework.core.ResolvableType;
import com.chorus.engine.annotation.GraphWorkflow;
import com.chorus.engine.graph.state.CompiledGraph;
import org.jspecify.annotations.NonNull;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.beans.factory.config.TypedStringValue;
import org.springframework.beans.factory.support.BeanDefinitionBuilder;
import org.springframework.beans.factory.support.BeanDefinitionRegistry;
import org.springframework.beans.factory.support.BeanDefinitionRegistryPostProcessor;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.AnnotationUtils;
import org.springframework.core.annotation.Order;

/**
 * Scans for {@link GraphWorkflow} classes and performs two responsibilities:
 * <ol>
 *   <li>Registers a {@link CompiledGraph} Spring bean via {@link GraphWorkflowFactoryBean}
 *       during {@code postProcessBeanDefinitionRegistry} (early phase — bean class only,
 *       no instantiation).</li>
 *   <li>Populates the {@link GraphDescriptorRegistry} during
 *       {@code afterSingletonsInstantiated} (safe phase — all properties bound,
 *       all singletons available), enabling the dev-mode graph visualizer.</li>
 * </ol>
 *
 * <p>Implementing {@link SmartInitializingSingleton} for the registry population ensures
 * AOT compatibility and avoids early-bean-instantiation hazards.
 */
@Order(Ordered.LOWEST_PRECEDENCE - 80)
public class GraphAnnotationProcessor
    implements BeanDefinitionRegistryPostProcessor, SmartInitializingSingleton {

    private ConfigurableListableBeanFactory beanFactory;

    @Override
    public void postProcessBeanDefinitionRegistry(@NonNull BeanDefinitionRegistry registry) throws BeansException {
        if (!(registry instanceof ConfigurableListableBeanFactory clbf)) {
            return;
        }
        for (String beanName : registry.getBeanDefinitionNames()) {
            BeanDefinition bd = registry.getBeanDefinition(beanName);
            Class<?> beanClass = resolveBeanClass(bd, clbf);
            if (beanClass == null) continue;

            GraphWorkflow wf = AnnotationUtils.findAnnotation(beanClass, GraphWorkflow.class);
            if (wf == null) continue;

            String compiledGraphBeanName = beanName + "_compiledGraph";
            if (registry.containsBeanDefinition(compiledGraphBeanName)) continue;

            // Use TypedStringValue for workflowClass so Spring AOT generates:
            //   new TypedStringValue("com.example.MyBean", Class.class)
            // instead of a direct Class literal (MyBean.class). A direct literal requires
            // importing the class in the generated __BeanDefinitions file — which fails at
            // compileAotJava when the workflow class is package-private and the generated
            // file lives in a different package (com.chorus.engine.graph.state).
            BeanDefinitionBuilder builder = BeanDefinitionBuilder.rootBeanDefinition(GraphWorkflowFactoryBean.class)
                .addPropertyValue("workflowBeanName", beanName)
                .addPropertyValue("workflowClass", new TypedStringValue(beanClass.getName(), Class.class))
                .addPropertyValue("entryPoint", wf.entryPoint())
                .addPropertyValue("finishPoints", wf.finishPoints());

            registry.registerBeanDefinition(compiledGraphBeanName, builder.getBeanDefinition());
        }
    }

    @Override
    public void postProcessBeanFactory(@NonNull ConfigurableListableBeanFactory beanFactory) throws BeansException {
        this.beanFactory = beanFactory;
    }

    /**
     * Populates {@link GraphDescriptorRegistry} after all singletons are instantiated.
     * This is safe for AOT and deferred until after configuration properties are bound.
     */
    @Override
    public void afterSingletonsInstantiated() {
        if (beanFactory == null) return;
        if (!beanFactory.containsBean("graphDescriptorRegistry")) return;

        GraphDescriptorRegistry descriptorRegistry =
            beanFactory.getBean("graphDescriptorRegistry", GraphDescriptorRegistry.class);

        for (String beanName : beanFactory.getBeanDefinitionNames()) {
            BeanDefinition bd = beanFactory.getBeanDefinition(beanName);
            Class<?> beanClass = resolveBeanClass(bd, beanFactory);
            if (beanClass == null) continue;

            GraphWorkflow wf = AnnotationUtils.findAnnotation(beanClass, GraphWorkflow.class);
            if (wf == null) continue;

            try {
                GraphDescriptor descriptor = GraphDescriptor.from(beanName, beanClass);
                descriptorRegistry.register(beanName, descriptor);
            } catch (Exception e) {
                // Log but do not crash startup — visualizer is a dev tool, not load-bearing
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
        ResolvableType resolvableType = bd.getResolvableType();
        if (resolvableType != ResolvableType.NONE) {
            Class<?> resolved = resolvableType.resolve();
            if (resolved != null) {
                return resolved;
            }
        }
        return null;
    }
}
