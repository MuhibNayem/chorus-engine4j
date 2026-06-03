package com.chorus.engine.springboot.graph;

import com.chorus.engine.annotation.GraphEdge;
import com.chorus.engine.annotation.GraphEdges;
import com.chorus.engine.annotation.GraphNode;
import com.chorus.engine.graph.state.CompiledGraph;
import com.chorus.engine.graph.state.StateGraph;
import org.springframework.beans.factory.FactoryBean;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ApplicationContextAware;
import org.springframework.core.annotation.AnnotationUtils;

import java.lang.reflect.Method;
import java.util.*;

public class GraphWorkflowFactoryBean implements FactoryBean<CompiledGraph<Map<String, Object>>>, ApplicationContextAware {

    private String workflowBeanName;
    private Class<?> workflowClass;
    private String entryPoint;
    private String[] finishPoints;
    private ApplicationContext applicationContext;

    public void setWorkflowBeanName(String workflowBeanName) { this.workflowBeanName = workflowBeanName; }
    /**
     * Receives the workflow class injected by Spring's type converter from the
     * {@link org.springframework.beans.factory.config.TypedStringValue} registered by
     * {@link GraphAnnotationProcessor}. Using TypedStringValue avoids a direct Class literal
     * in AOT-generated code, which would fail compileAotJava for package-private classes.
     */
    public void setWorkflowClass(Class<?> workflowClass) { this.workflowClass = workflowClass; }
    /** Backward-compatibility setter for String class name (older configurations). */
    public void setWorkflowClassName(String workflowClassName) {
        try {
            this.workflowClass = Class.forName(workflowClassName, true,
                Thread.currentThread().getContextClassLoader());
        } catch (ClassNotFoundException e) {
            throw new IllegalArgumentException("Cannot load workflow class: " + workflowClassName, e);
        }
    }
    public void setEntryPoint(String entryPoint) { this.entryPoint = entryPoint; }
    public void setFinishPoints(String[] finishPoints) { this.finishPoints = finishPoints; }

    @Override
    public void setApplicationContext(ApplicationContext applicationContext) {
        this.applicationContext = applicationContext;
    }

    @Override
    @SuppressWarnings("unchecked")
    public CompiledGraph<Map<String, Object>> getObject() throws Exception {
        if (workflowClass == null) {
            throw new IllegalStateException("workflowClass has not been set on GraphWorkflowFactoryBean");
        }
        StateGraph<Map<String, Object>> stateGraph = new StateGraph<>(
            (current, update) -> {
                Map<String, Object> result = new LinkedHashMap<>(current);
                result.putAll(update);
                return result;
            }
        );

        // Register nodes
        for (Method method : workflowClass.getDeclaredMethods()) {
            GraphNode node = AnnotationUtils.findAnnotation(method, GraphNode.class);
            if (node == null) continue;
            method.setAccessible(true);
            stateGraph.addNode(node.value(), (state, token) -> {
                try {
                    Object target = applicationContext.getBean(workflowBeanName);
                    return (Map<String, Object>) method.invoke(target, state, token);
                } catch (Exception e) {
                    throw new RuntimeException("Graph node " + node.value() + " failed", e);
                }
            });
        }

        // Register edges
        GraphEdge[] edges = collectEdges(workflowClass);
        for (GraphEdge edge : edges) {
            stateGraph.addEdge(edge.from(), edge.to());
        }

        stateGraph.setEntryPoint(entryPoint);
        for (String finish : finishPoints) {
            stateGraph.setFinishPoint(finish);
        }

        return stateGraph.compile();
    }

    @Override
    public Class<?> getObjectType() {
        return CompiledGraph.class;
    }

    @Override
    public boolean isSingleton() {
        return true;
    }

    private GraphEdge[] collectEdges(Class<?> clazz) {
        List<GraphEdge> edges = new ArrayList<>();
        GraphEdge single = AnnotationUtils.findAnnotation(clazz, GraphEdge.class);
        if (single != null) edges.add(single);
        GraphEdges multiple = AnnotationUtils.findAnnotation(clazz, GraphEdges.class);
        if (multiple != null) edges.addAll(Arrays.asList(multiple.value()));
        return edges.toArray(new GraphEdge[0]);
    }
}
