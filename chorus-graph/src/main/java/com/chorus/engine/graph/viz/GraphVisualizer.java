package com.chorus.engine.graph.viz;

import com.chorus.engine.graph.StateGraph;

/**
 * Exports a {@link StateGraph} to Mermaid and PlantUML syntax.
 */
public class GraphVisualizer {

    private GraphVisualizer() {
        // utility
    }

    public static <S> String toMermaid(StateGraph<S> graph) {
        StringBuilder sb = new StringBuilder();
        sb.append("flowchart TD\n");

        // Special nodes
        sb.append("    __start__([\"__start__\"])\n");
        sb.append("    __end__([\"__end__\"])\n");

        // Regular nodes
        for (String node : graph.nodes().keySet()) {
            String id = sanitizeId(node);
            sb.append("    ").append(id).append("[\"").append(escapeLabel(node)).append("\"]\n");
        }

        // Conditional indicator nodes
        for (var ce : graph.conditionalEdges()) {
            String id = sanitizeId(ce.from()) + "_cond";
            sb.append("    ").append(id).append("{{\"?\"}}\n");
        }

        String entry = graph.entryPoint();

        // Entry-point edges
        if (StateGraph.START.equals(entry)) {
            for (var e : graph.edges()) {
                if (e.from().equals(StateGraph.START)) {
                    sb.append("    __start__ --> ").append(sanitizeId(e.to())).append("\n");
                }
            }
        } else {
            sb.append("    __start__ --> ").append(sanitizeId(entry)).append("\n");
        }

        // Static edges
        for (var e : graph.edges()) {
            if (!e.from().equals(StateGraph.START)) {
                sb.append("    ").append(sanitizeId(e.from()))
                    .append(" --> ").append(sanitizeId(e.to())).append("\n");
            }
        }

        // Conditional edges (dotted)
        for (var ce : graph.conditionalEdges()) {
            sb.append("    ").append(sanitizeId(ce.from()))
                .append(" -.-> ").append(sanitizeId(ce.from()) + "_cond").append("\n");
        }

        return sb.toString();
    }

    public static <S> String toPlantUml(StateGraph<S> graph) {
        StringBuilder sb = new StringBuilder();
        sb.append("@startuml\n");
        sb.append("skinparam rectangle {\n");
        sb.append("    BackgroundColor<<start>> LightGreen\n");
        sb.append("    BackgroundColor<<end>> LightCoral\n");
        sb.append("}\n");

        sb.append("rectangle \"__start__\" <<start>>\n");
        sb.append("rectangle \"__end__\" <<end>>\n");

        for (String node : graph.nodes().keySet()) {
            sb.append("rectangle \"").append(escapePlantUml(node)).append("\"\n");
        }

        // Conditional indicator nodes
        for (var ce : graph.conditionalEdges()) {
            sb.append("rectangle \"?\" as ").append(sanitizeId(ce.from())).append("_cond\n");
        }

        String entry = graph.entryPoint();

        if (StateGraph.START.equals(entry)) {
            for (var e : graph.edges()) {
                if (e.from().equals(StateGraph.START)) {
                    sb.append("\"__start__\" --> \"").append(escapePlantUml(e.to())).append("\"\n");
                }
            }
        } else {
            sb.append("\"__start__\" --> \"").append(escapePlantUml(entry)).append("\"\n");
        }

        for (var e : graph.edges()) {
            if (!e.from().equals(StateGraph.START)) {
                sb.append("\"").append(escapePlantUml(e.from())).append("\" --> \"")
                    .append(escapePlantUml(e.to())).append("\"\n");
            }
        }

        for (var ce : graph.conditionalEdges()) {
            sb.append("\"").append(escapePlantUml(ce.from())).append("\" ..> ")
                .append(sanitizeId(ce.from())).append("_cond : condition\n");
        }

        sb.append("@enduml\n");
        return sb.toString();
    }

    private static String sanitizeId(String id) {
        return id.replaceAll("[^a-zA-Z0-9_]", "_");
    }

    private static String escapeLabel(String label) {
        return label.replace("\"", "#quot;");
    }

    private static String escapePlantUml(String text) {
        return text.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
