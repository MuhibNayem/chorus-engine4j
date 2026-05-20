package com.chorus.engine.springboot;

import com.chorus.engine.springboot.graph.GraphDescriptor;
import com.chorus.engine.springboot.graph.GraphDescriptorRegistry;
import com.chorus.engine.springboot.graph.GraphMermaidRenderer;
import org.jspecify.annotations.NonNull;
import org.springframework.context.annotation.Profile;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Dev-mode REST controller serving the Chorus Graph Visualizer UI.
 *
 * <p>Only active under the {@code dev}, {@code local}, or {@code default} Spring profiles.
 * It is structurally impossible to enable this controller in a {@code prod} environment.
 *
 * <h2>Endpoints</h2>
 * <ul>
 *   <li>{@code GET /chorus/visualizer} — serves the interactive HTML UI</li>
 *   <li>{@code GET /chorus/visualizer/api/graphs} — JSON list of all discovered workflows</li>
 *   <li>{@code GET /chorus/visualizer/api/graphs/{beanName}/mermaid} — Mermaid source for one workflow</li>
 *   <li>{@code GET /chorus/visualizer/api/graphs/{beanName}/mermaid/all} — combined Mermaid source</li>
 * </ul>
 */
@RestController
@RequestMapping("/chorus/visualizer")
@Profile({"dev", "local", "default"})
public class GraphVisualizerController {

    private final GraphDescriptorRegistry registry;
    private final GraphMermaidRenderer renderer;

    public GraphVisualizerController(
        @NonNull GraphDescriptorRegistry registry,
        @NonNull GraphMermaidRenderer renderer
    ) {
        this.registry = registry;
        this.renderer = renderer;
    }

    /**
     * Serves the self-contained HTML visualizer page.
     *
     * @return the full HTML content with embedded Mermaid.js
     */
    @GetMapping(produces = MediaType.TEXT_HTML_VALUE)
    public ResponseEntity<String> visualizerPage() throws IOException {
        ClassPathResource resource = new ClassPathResource("chorus/visualizer.html");
        String html = resource.getContentAsString(StandardCharsets.UTF_8);
        return ResponseEntity.ok()
            .contentType(new MediaType("text", "html", StandardCharsets.UTF_8))
            .body(html);
    }

    /**
     * Returns metadata for all discovered {@code @GraphWorkflow} beans.
     *
     * @return JSON list of workflow summaries
     */
    @GetMapping(path = "/api/graphs", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<List<GraphSummaryResponse>> listGraphs() {
        Collection<GraphDescriptor> all = registry.allSorted();
        List<GraphSummaryResponse> response = all.stream()
            .map(d -> new GraphSummaryResponse(
                d.beanName(),
                d.simpleClassName(),
                d.workflowClassName(),
                d.entryPoint(),
                d.finishPoints(),
                d.nodes().stream().map(GraphDescriptor.NodeDescriptor::name).toList(),
                d.edges().size(),
                d.conditionalEdges().size(),
                d.metadata()
            ))
            .toList();
        return ResponseEntity.ok(response);
    }

    /**
     * Returns the Mermaid flowchart source for a single workflow.
     *
     * @param beanName the Spring bean name of the workflow
     * @return plain-text Mermaid diagram
     */
    @GetMapping(path = "/api/graphs/{beanName}/mermaid", produces = MediaType.TEXT_PLAIN_VALUE)
    public ResponseEntity<String> getMermaid(@PathVariable @NonNull String beanName) {
        Optional<GraphDescriptor> descriptor = registry.find(beanName);
        if (descriptor.isEmpty()) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok(renderer.render(descriptor.get()));
    }

    /**
     * Returns a combined Mermaid diagram for all discovered workflows.
     *
     * @return plain-text Mermaid diagram with one subgraph per workflow
     */
    @GetMapping(path = "/api/graphs/mermaid/all", produces = MediaType.TEXT_PLAIN_VALUE)
    public ResponseEntity<String> getAllMermaid() {
        return ResponseEntity.ok(renderer.renderAll(registry.allSorted()));
    }

    // ── Response records ─────────────────────────────────────────────

    /**
     * JSON summary of a single discovered workflow.
     */
    public record GraphSummaryResponse(
        @NonNull String beanName,
        @NonNull String simpleClassName,
        @NonNull String workflowClassName,
        @NonNull String entryPoint,
        @NonNull List<String> finishPoints,
        @NonNull List<String> nodeNames,
        int edgeCount,
        int conditionalEdgeCount,
        @NonNull Map<String, String> metadata
    ) {}
}
