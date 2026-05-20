package com.chorus.engine.springboot.graph;
import org.springframework.core.ResolvableType;

import com.chorus.engine.annotation.GraphEdge;
import com.chorus.engine.annotation.GraphEdges;
import com.chorus.engine.annotation.GraphNode;
import com.chorus.engine.annotation.GraphWorkflow;
import com.chorus.engine.core.reactive.CancellationToken;
import com.chorus.engine.graph.state.CompiledGraph;
import com.chorus.engine.graph.state.StateGraph;
import org.jspecify.annotations.NonNull;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.beans.factory.support.BeanDefinitionRegistry;
import org.springframework.beans.factory.support.BeanDefinitionRegistryPostProcessor;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.AnnotationUtils;
import org.springframework.core.annotation.Order;

import java.lang.reflect.Method;
import java.util.*;

/**
 * Scans for {@link GraphWorkflow} classes, collects {@link GraphNode} methods
 * and {@link GraphEdge} annotations, builds a {@link StateGraph}, compiles it,
 * and registers the {@code CompiledGraph} bean.
 */
@Order(Ordered.LOWEST_PRECEDENCE - 80)
public class GraphAnnotationProcessor implements BeanDefinitionRegistryPostProcessor {

    @Override
    public void postProcessBeanDefinitionRegistry(@NonNull BeanDefinitionRegistry registry) throws BeansException {
        // Discovery deferred to postProcessBeanFactory where bean instances are available
    }

    @Override
    @SuppressWarnings({"unchecked"})
    public void postProcessBeanFactory(@NonNull ConfigurableListableBeanFactory beanFactory) throws BeansException {
        for (String beanName : beanFactory.getBeanDefinitionNames()) {
            BeanDefinition bd = beanFactory.getBeanDefinition(beanName);
            Class<?> beanClass = resolveBeanClass(bd, beanFactory);
            if (beanClass == null) continue;

            GraphWorkflow wf = AnnotationUtils.findAnnotation(beanClass, GraphWorkflow.class);
            if (wf == null) continue;

            String compiledGraphBeanName = beanName + "_compiledGraph";
            if (beanFactory.containsBean(compiledGraphBeanName)) continue;

            Object target = beanFactory.getBean(beanName);

            StateGraph<Map<String, Object>> stateGraph = new StateGraph<>(
                (current, update) -> {
                    Map<String, Object> result = new LinkedHashMap<>(current);
                    result.putAll(update);
                    return result;
                }
            );

            // Register nodes
            for (Method method : beanClass.getDeclaredMethods()) {
                GraphNode node = AnnotationUtils.findAnnotation(method, GraphNode.class);
                if (node == null) continue;
                method.setAccessible(true);
                stateGraph.addNode(node.value(), (state, token) -> {
                    try {
                        return (Map<String, Object>) method.invoke(target, state, token);
                    } catch (Exception e) {
                        throw new RuntimeException("Graph node " + node.value() + " failed", e);
                    }
                });
            }

            // Register edges
            GraphEdge[] edges = collectEdges(beanClass);
            for (GraphEdge edge : edges) {
                stateGraph.addEdge(edge.from(), edge.to());
            }

            stateGraph.setEntryPoint(wf.entryPoint());
            for (String finish : wf.finishPoints()) {
                stateGraph.setFinishPoint(finish);
            }

            CompiledGraph<Map<String, Object>> compiledGraph = stateGraph.compile();
            beanFactory.registerSingleton(compiledGraphBeanName, compiledGraph);
        }
    }

    private GraphEdge[] collectEdges(Class<?> clazz) {
        List<GraphEdge> edges = new ArrayList<>();
        GraphEdge single = AnnotationUtils.findAnnotation(clazz, GraphEdge.class);
        if (single != null) edges.add(single);
        GraphEdges multiple = AnnotationUtils.findAnnotation(clazz, GraphEdges.class);
        if (multiple != null) edges.addAll(Arrays.asList(multiple.value()));
        return edges.toArray(new GraphEdge[0]);
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
