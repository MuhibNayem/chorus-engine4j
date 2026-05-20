package com.chorus.engine.springboot.graph;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.*;

/**
 * Unit tests for {@link GraphMermaidRenderer}.
 *
 * <p>Verifies correct Mermaid.js flowchart syntax output, node colour coding,
 * edge rendering, subgraph multi-workflow composition, and node name sanitization.
 */
@DisplayName("GraphMermaidRenderer")
class GraphMermaidRendererTest {

    private final GraphMermaidRenderer renderer = new GraphMermaidRenderer();

    // ── Single workflow ───────────────────────────────────────────────

    @Test
    @DisplayName("renders flowchart header")
    void rendersFlowchartHeader() {
        GraphDescriptor descriptor = buildDescriptor("myWorkflow", "extract", List.of("review"),
            List.of(
                new GraphDescriptor.NodeDescriptor("extract", "extract"),
                new GraphDescriptor.NodeDescriptor("generate", "generate"),
                new GraphDescriptor.NodeDescriptor("review", "review")
            ),
            List.of(
                new GraphDescriptor.EdgeDescriptor("extract", "generate"),
                new GraphDescriptor.EdgeDescriptor("generate", "review")
            )
        );

        String result = renderer.render(descriptor);

        assertThat(result).startsWith("flowchart TD");
    }

    @Test
    @DisplayName("renders __start__ sentinel connected to entry point")
    void rendersSentinelToEntry() {
        GraphDescriptor descriptor = buildDescriptor("wf", "extract", List.of("review"),
            List.of(new GraphDescriptor.NodeDescriptor("extract", "extract"),
                    new GraphDescriptor.NodeDescriptor("review", "review")),
            List.of(new GraphDescriptor.EdgeDescriptor("extract", "review"))
        );

        String result = renderer.render(descriptor);

        assertThat(result).contains("__start__");
        assertThat(result).contains("__start__ --> extract");
    }

    @Test
    @DisplayName("renders all edges")
    void rendersAllEdges() {
        GraphDescriptor descriptor = buildDescriptor("wf", "a", List.of("c"),
            List.of(new GraphDescriptor.NodeDescriptor("a", "a"),
                    new GraphDescriptor.NodeDescriptor("b", "b"),
                    new GraphDescriptor.NodeDescriptor("c", "c")),
            List.of(
                new GraphDescriptor.EdgeDescriptor("a", "b"),
                new GraphDescriptor.EdgeDescriptor("b", "c")
            )
        );

        String result = renderer.render(descriptor);

        assertThat(result).contains("a --> b");
        assertThat(result).contains("b --> c");
    }

    @Test
    @DisplayName("applies amber style to entry node")
    void stylesEntryNodeAmber() {
        GraphDescriptor descriptor = buildDescriptor("wf", "start", List.of("end"),
            List.of(new GraphDescriptor.NodeDescriptor("start", "start"),
                    new GraphDescriptor.NodeDescriptor("end", "end")),
            List.of(new GraphDescriptor.EdgeDescriptor("start", "end"))
        );

        String result = renderer.render(descriptor);

        assertThat(result).contains("style start fill:#f59e0b");
    }

    @Test
    @DisplayName("applies emerald style to finish nodes")
    void stylesFinishNodeEmerald() {
        GraphDescriptor descriptor = buildDescriptor("wf", "process", List.of("done"),
            List.of(new GraphDescriptor.NodeDescriptor("process", "process"),
                    new GraphDescriptor.NodeDescriptor("done", "done")),
            List.of(new GraphDescriptor.EdgeDescriptor("process", "done"))
        );

        String result = renderer.render(descriptor);

        assertThat(result).contains("style done fill:#10b981");
    }

    @Test
    @DisplayName("applies indigo style to normal (non-entry, non-finish) nodes")
    void stylesNormalNodeIndigo() {
        GraphDescriptor descriptor = buildDescriptor("wf", "a", List.of("c"),
            List.of(new GraphDescriptor.NodeDescriptor("a", "a"),
                    new GraphDescriptor.NodeDescriptor("b", "b"),    // ← normal
                    new GraphDescriptor.NodeDescriptor("c", "c")),
            List.of(new GraphDescriptor.EdgeDescriptor("a", "b"),
                    new GraphDescriptor.EdgeDescriptor("b", "c"))
        );

        String result = renderer.render(descriptor);

        assertThat(result).contains("style b fill:#6366f1");
    }

    @Test
    @DisplayName("renders finish nodes as rounded (ellipse) boxes")
    void rendersFinishNodesAsRounded() {
        GraphDescriptor descriptor = buildDescriptor("wf", "start", List.of("finish"),
            List.of(new GraphDescriptor.NodeDescriptor("start", "start"),
                    new GraphDescriptor.NodeDescriptor("finish", "finish")),
            List.of(new GraphDescriptor.EdgeDescriptor("start", "finish"))
        );

        String result = renderer.render(descriptor);

        // Finish nodes rendered as ([...])
        assertThat(result).contains("finish([\"finish\"])");
    }

    @Test
    @DisplayName("renders normal nodes as rectangle boxes")
    void rendersNormalNodesAsRectangles() {
        GraphDescriptor descriptor = buildDescriptor("wf", "entry", List.of("exit"),
            List.of(new GraphDescriptor.NodeDescriptor("entry", "entry"),
                    new GraphDescriptor.NodeDescriptor("middle", "middle"),
                    new GraphDescriptor.NodeDescriptor("exit", "exit")),
            List.of(new GraphDescriptor.EdgeDescriptor("entry", "middle"),
                    new GraphDescriptor.EdgeDescriptor("middle", "exit"))
        );

        String result = renderer.render(descriptor);

        assertThat(result).contains("middle[\"middle\"]");
    }

    // ── Multi-workflow ────────────────────────────────────────────────

    @Test
    @DisplayName("renders single descriptor without subgraph wrapper")
    void singleDescriptorNoSubgraph() {
        GraphDescriptor descriptor = buildDescriptor("wf", "a", List.of("b"),
            List.of(new GraphDescriptor.NodeDescriptor("a", "a"),
                    new GraphDescriptor.NodeDescriptor("b", "b")),
            List.of(new GraphDescriptor.EdgeDescriptor("a", "b"))
        );

        String result = renderer.renderAll(List.of(descriptor));

        assertThat(result).doesNotContain("subgraph");
    }

    @Test
    @DisplayName("renders multiple descriptors with subgraph per workflow")
    void multipleDescriptorsUseSubgraphs() {
        GraphDescriptor wf1 = buildDescriptor("wf1", "a", List.of("b"),
            List.of(new GraphDescriptor.NodeDescriptor("a", "a"),
                    new GraphDescriptor.NodeDescriptor("b", "b")),
            List.of(new GraphDescriptor.EdgeDescriptor("a", "b"))
        );
        GraphDescriptor wf2 = buildDescriptor("wf2", "x", List.of("y"),
            List.of(new GraphDescriptor.NodeDescriptor("x", "x"),
                    new GraphDescriptor.NodeDescriptor("y", "y")),
            List.of(new GraphDescriptor.EdgeDescriptor("x", "y"))
        );

        String result = renderer.renderAll(List.of(wf1, wf2));

        assertThat(result).contains("subgraph");
    }

    @Test
    @DisplayName("renders empty collection with informative placeholder")
    void emptyCollectionPlaceholder() {
        String result = renderer.renderAll(List.of());

        assertThat(result).startsWith("flowchart TD");
        assertThat(result).containsIgnoringCase("No @GraphWorkflow beans detected");
    }

    // ── Sanitization ─────────────────────────────────────────────────

    @ParameterizedTest
    @ValueSource(strings = {"my node", "my-node", "my.node"})
    @DisplayName("sanitizes node names with spaces, hyphens, dots to underscores")
    void sanitizesNodeNamesToUnderscores(String name) {
        String result = GraphMermaidRenderer.sanitize(name);
        assertThat(result).doesNotContain(" ", "-", ".");
        assertThat(result).contains("_");
    }

    @Test
    @DisplayName("sanitize returns lowercase result")
    void sanitizeLowercase() {
        assertThat(GraphMermaidRenderer.sanitize("ExtractNode")).isEqualTo("extractnode");
    }

    @Test
    @DisplayName("sanitize strips non-alphanumeric characters")
    void sanitizeStripsSpecialChars() {
        String result = GraphMermaidRenderer.sanitize("node@#$123");
        assertThat(result).doesNotContainPattern("[^a-z0-9_]");
        assertThat(result).contains("node");
        assertThat(result).contains("123");
    }

    // ── Helpers ───────────────────────────────────────────────────────

    private static GraphDescriptor buildDescriptor(
        String beanName,
        String entryPoint,
        List<String> finishPoints,
        List<GraphDescriptor.NodeDescriptor> nodes,
        List<GraphDescriptor.EdgeDescriptor> edges
    ) {
        return new GraphDescriptor(
            beanName,
            "com.example." + beanName,
            beanName,
            entryPoint,
            finishPoints,
            nodes,
            edges,
            List.of(),
            Map.of("stateType", "Map")
        );
    }
}
