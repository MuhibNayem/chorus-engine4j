package com.chorus.engine.springboot.graph;

import org.jspecify.annotations.NonNull;

import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Converts {@link GraphDescriptor} instances into valid Mermaid.js flowchart syntax.
 *
 * <p>This is a pure-function service with zero Spring dependencies, fully unit-testable
 * in isolation. It produces {@code flowchart TD} diagrams with visual distinction
 * between entry, finish, and intermediate nodes.
 *
 * <h2>Node Styling</h2>
 * <ul>
 *   <li><b>Entry node</b>: amber ({@code #f59e0b})</li>
 *   <li><b>Finish nodes</b>: emerald ({@code #10b981})</li>
 *   <li><b>Normal nodes</b>: indigo ({@code #6366f1})</li>
 *   <li><b>__start__ sentinel</b>: slate with rounded box</li>
 * </ul>
 *
 * <h2>Usage</h2>
 * <pre>{@code
 * GraphMermaidRenderer renderer = new GraphMermaidRenderer();
 * String diagram = renderer.render(descriptor);
 * }</pre>
 */
public final class GraphMermaidRenderer {

    // Node colour tokens
    private static final String COLOR_ENTRY   = "#f59e0b";
    private static final String COLOR_FINISH  = "#10b981";
    private static final String COLOR_NORMAL  = "#6366f1";
    private static final String COLOR_START   = "#64748b";
    private static final String TEXT_LIGHT    = "#ffffff";

    /**
     * Renders a single {@link GraphDescriptor} as a Mermaid {@code flowchart TD} string.
     *
     * @param descriptor the workflow topology descriptor
     * @return a valid Mermaid diagram string
     */
    public @NonNull String render(@NonNull GraphDescriptor descriptor) {
        StringBuilder sb = new StringBuilder();
        sb.append("flowchart TD\n");

        Set<String> finishSet = Set.copyOf(descriptor.finishPoints());
        String entry = descriptor.entryPoint();

        // __start__ sentinel → entry node
        sb.append("    __start__([\"▶ Start\"])\n");
        sb.append("    __start__ --> ").append(sanitize(entry)).append("\n");

        // Declare nodes with display labels
        for (GraphDescriptor.NodeDescriptor node : descriptor.nodes()) {
            String id = sanitize(node.name());
            String label = node.name();
            if (finishSet.contains(node.name())) {
                sb.append("    ").append(id).append("([\"").append(label).append("\"])\n");
            } else {
                sb.append("    ").append(id).append("[\"").append(label).append("\"]\n");
            }
        }

        // Edges
        for (GraphDescriptor.EdgeDescriptor edge : descriptor.edges()) {
            sb.append("    ").append(sanitize(edge.from()))
              .append(" --> ").append(sanitize(edge.to())).append("\n");
        }

        // Conditional edges (dashed)
        for (GraphDescriptor.ConditionalEdgeDescriptor cEdge : descriptor.conditionalEdges()) {
            for (String dest : cEdge.possibleDestinations()) {
                sb.append("    ").append(sanitize(cEdge.from()))
                  .append(" -.-> ").append(sanitize(dest)).append("\n");
            }
        }

        // Styles
        sb.append("\n");
        sb.append("    style __start__ fill:").append(COLOR_START)
          .append(",color:").append(TEXT_LIGHT).append(",stroke:none\n");

        for (GraphDescriptor.NodeDescriptor node : descriptor.nodes()) {
            String id = sanitize(node.name());
            String fill;
            if (node.name().equals(entry)) {
                fill = COLOR_ENTRY;
            } else if (finishSet.contains(node.name())) {
                fill = COLOR_FINISH;
            } else {
                fill = COLOR_NORMAL;
            }
            sb.append("    style ").append(id)
              .append(" fill:").append(fill)
              .append(",color:").append(TEXT_LIGHT)
              .append(",stroke:none\n");
        }

        return sb.toString();
    }

    /**
     * Renders all descriptors as a single Mermaid diagram, placing each workflow in
     * a labelled {@code subgraph} block.
     *
     * @param descriptors the collection of workflow descriptors
     * @return a combined Mermaid diagram string
     */
    public @NonNull String renderAll(@NonNull Collection<GraphDescriptor> descriptors) {
        if (descriptors.isEmpty()) {
            return "flowchart TD\n    empty[\"No @GraphWorkflow beans detected\"]\n";
        }
        if (descriptors.size() == 1) {
            return render(descriptors.iterator().next());
        }

        StringBuilder sb = new StringBuilder();
        sb.append("flowchart TD\n");

        int subgraphIndex = 0;
        for (GraphDescriptor descriptor : descriptors) {
            String subId = "sg" + subgraphIndex++;
            sb.append("\n    subgraph ").append(subId)
              .append("[\"").append(escapeLabel(descriptor.simpleClassName())).append("\"]\n");
            sb.append("    direction TD\n");

            Set<String> finishSet = Set.copyOf(descriptor.finishPoints());
            String entry = descriptor.entryPoint();
            String prefix = subId + "_";

            // Nodes
            sb.append("    ").append(prefix).append("__start__([\"▶ Start\"])\n");
            sb.append("    ").append(prefix).append("__start__ --> ")
              .append(prefix).append(sanitize(entry)).append("\n");

            for (GraphDescriptor.NodeDescriptor node : descriptor.nodes()) {
                String id = prefix + sanitize(node.name());
                String label = node.name();
                if (finishSet.contains(node.name())) {
                    sb.append("    ").append(id).append("([\"").append(label).append("\"])\n");
                } else {
                    sb.append("    ").append(id).append("[\"").append(label).append("\"]\n");
                }
            }

            // Edges
            for (GraphDescriptor.EdgeDescriptor edge : descriptor.edges()) {
                sb.append("    ").append(prefix).append(sanitize(edge.from()))
                  .append(" --> ").append(prefix).append(sanitize(edge.to())).append("\n");
            }

            sb.append("    end\n");

            // Styles (must be outside subgraph)
            sb.append("    style ").append(prefix).append("__start__ fill:")
              .append(COLOR_START).append(",color:").append(TEXT_LIGHT).append(",stroke:none\n");

            for (GraphDescriptor.NodeDescriptor node : descriptor.nodes()) {
                String id = prefix + sanitize(node.name());
                String fill = node.name().equals(entry) ? COLOR_ENTRY
                    : finishSet.contains(node.name()) ? COLOR_FINISH : COLOR_NORMAL;
                sb.append("    style ").append(id)
                  .append(" fill:").append(fill)
                  .append(",color:").append(TEXT_LIGHT)
                  .append(",stroke:none\n");
            }
        }

        return sb.toString();
    }

    // ── Sanitisation helpers ──────────────────────────────────────────

    /**
     * Converts a node name to a valid Mermaid node identifier.
     * Replaces spaces, hyphens, and dots with underscores; strips non-alphanumeric chars.
     */
    static @NonNull String sanitize(@NonNull String name) {
        return name
            .replaceAll("[\\s\\-.]", "_")
            .replaceAll("[^a-zA-Z0-9_]", "")
            .toLowerCase(Locale.ROOT);
    }

    private static @NonNull String escapeLabel(@NonNull String label) {
        return label.replace("\"", "'");
    }
}
