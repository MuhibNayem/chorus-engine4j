package com.chorus.engine.springboot.graph;

import com.chorus.engine.annotation.GraphEdge;
import com.chorus.engine.annotation.GraphEdges;
import com.chorus.engine.annotation.GraphNode;
import com.chorus.engine.annotation.GraphWorkflow;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Immutable topology descriptor for a {@link GraphWorkflow} bean.
 *
 * <p>Captures all structural metadata — nodes, edges, entry/finish points — at
 * application startup without requiring the workflow class to be instantiated.
 * Used exclusively by the dev-mode visualizer; has no impact on graph execution.
 *
 * @param beanName           Spring bean name of the workflow component
 * @param workflowClassName  fully-qualified class name of the annotated class
 * @param simpleClassName    simple class name (for display purposes)
 * @param entryPoint         name of the entry-point node
 * @param finishPoints       names of terminal nodes
 * @param nodes              ordered list of node descriptors
 * @param edges              unconditional edges
 * @param conditionalEdges   conditional routing edges
 * @param metadata           arbitrary key-value metadata (e.g. stateType)
 */
public record GraphDescriptor(
    @NonNull String beanName,
    @NonNull String workflowClassName,
    @NonNull String simpleClassName,
    @NonNull String entryPoint,
    @NonNull List<String> finishPoints,
    @NonNull List<NodeDescriptor> nodes,
    @NonNull List<EdgeDescriptor> edges,
    @NonNull List<ConditionalEdgeDescriptor> conditionalEdges,
    @NonNull Map<String, String> metadata
) {

    public GraphDescriptor {
        Objects.requireNonNull(beanName, "beanName");
        Objects.requireNonNull(workflowClassName, "workflowClassName");
        Objects.requireNonNull(simpleClassName, "simpleClassName");
        Objects.requireNonNull(entryPoint, "entryPoint");
        finishPoints = List.copyOf(Objects.requireNonNull(finishPoints, "finishPoints"));
        nodes = List.copyOf(Objects.requireNonNull(nodes, "nodes"));
        edges = List.copyOf(Objects.requireNonNull(edges, "edges"));
        conditionalEdges = List.copyOf(Objects.requireNonNull(conditionalEdges, "conditionalEdges"));
        metadata = Map.copyOf(Objects.requireNonNull(metadata, "metadata"));
    }

    /**
     * A single graph node, derived from a {@link GraphNode}-annotated method.
     *
     * @param name        the node identifier (used in edges and entry/finish points)
     * @param methodName  name of the Java method implementing this node
     */
    public record NodeDescriptor(
        @NonNull String name,
        @NonNull String methodName
    ) {
        public NodeDescriptor {
            Objects.requireNonNull(name, "name");
            Objects.requireNonNull(methodName, "methodName");
        }
    }

    /**
     * An unconditional directed edge, derived from a {@link GraphEdge} annotation.
     */
    public record EdgeDescriptor(
        @NonNull String from,
        @NonNull String to
    ) {
        public EdgeDescriptor {
            Objects.requireNonNull(from, "from");
            Objects.requireNonNull(to, "to");
        }
    }

    /**
     * A conditional routing edge — placeholder for future {@code @GraphConditionalEdge} support.
     */
    public record ConditionalEdgeDescriptor(
        @NonNull String from,
        @NonNull List<String> possibleDestinations,
        @Nullable String routerDescription
    ) {
        public ConditionalEdgeDescriptor {
            Objects.requireNonNull(from, "from");
            possibleDestinations = List.copyOf(Objects.requireNonNull(possibleDestinations, "possibleDestinations"));
        }
    }

    /**
     * Builds a {@code GraphDescriptor} by reflectively scanning a {@link GraphWorkflow}-annotated class.
     *
     * @param beanName   the Spring bean name
     * @param beanClass  the workflow class (must have {@code @GraphWorkflow})
     * @return a fully-populated descriptor
     * @throws IllegalArgumentException if the class is not annotated with {@code @GraphWorkflow}
     */
    public static @NonNull GraphDescriptor from(@NonNull String beanName, @NonNull Class<?> beanClass) {
        GraphWorkflow wf = beanClass.getAnnotation(GraphWorkflow.class);
        if (wf == null) {
            throw new IllegalArgumentException(
                "Class " + beanClass.getName() + " is not annotated with @GraphWorkflow");
        }

        // Collect nodes from @GraphNode-annotated methods
        List<NodeDescriptor> nodes = new java.util.ArrayList<>();
        for (java.lang.reflect.Method method : beanClass.getDeclaredMethods()) {
            GraphNode nodeAnn = method.getAnnotation(GraphNode.class);
            if (nodeAnn != null) {
                nodes.add(new NodeDescriptor(nodeAnn.value(), method.getName()));
            }
        }

        // Collect edges from @GraphEdge / @GraphEdges on the class
        List<EdgeDescriptor> edges = new java.util.ArrayList<>();
        GraphEdge singleEdge = beanClass.getAnnotation(GraphEdge.class);
        if (singleEdge != null) {
            edges.add(new EdgeDescriptor(singleEdge.from(), singleEdge.to()));
        }
        GraphEdges multipleEdges = beanClass.getAnnotation(GraphEdges.class);
        if (multipleEdges != null) {
            for (GraphEdge e : multipleEdges.value()) {
                edges.add(new EdgeDescriptor(e.from(), e.to()));
            }
        }

        Map<String, String> meta = Map.of(
            "stateType", wf.stateType().getSimpleName()
        );

        return new GraphDescriptor(
            beanName,
            beanClass.getName(),
            beanClass.getSimpleName(),
            wf.entryPoint(),
            List.of(wf.finishPoints()),
            nodes,
            edges,
            List.of(),  // conditional edges: scanning support future @GraphConditionalEdge
            meta
        );
    }
}
