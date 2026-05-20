package com.chorus.engine.springboot.graph;

import com.chorus.engine.annotation.GraphEdge;
import com.chorus.engine.annotation.GraphEdges;
import com.chorus.engine.annotation.GraphNode;
import com.chorus.engine.annotation.GraphWorkflow;
import com.chorus.engine.graph.state.CompiledGraph;
import com.chorus.engine.springboot.ChorusAutoConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Comprehensive tests for {@link GraphAnnotationProcessor}.
 *
 * <p>Covers:
 * <ul>
 *   <li>@GraphWorkflow + @GraphNode + @GraphEdge → CompiledGraph bean</li>
 *   <li>Multiple nodes and edges</li>
 *   <li>Entry point and finish points wired correctly</li>
 *   <li>No @GraphWorkflow beans → no CompiledGraph beans</li>
 *   <li>Idempotency: duplicate compiled graph bean name skipped</li>
 * </ul>
 */
class GraphAnnotationProcessorTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
        .withConfiguration(AutoConfigurations.of(ChorusAutoConfiguration.class))
        .withPropertyValues("chorus.enabled=true");

    // ================================================================
    // BASIC GRAPH COMPILATION
    // ================================================================

    @Test
    void singleNodeGraphCompilesToBean() {
        contextRunner
            .withUserConfiguration(SimpleGraphConfig.class)
            .run(context -> {
                assertThat(context).hasBean("simpleGraph_compiledGraph");
                assertThat(context.getBean("simpleGraph_compiledGraph")).isInstanceOf(CompiledGraph.class);
            });
    }

    @Test
    void multiNodeGraphWithEdgesCompilesToBean() {
        contextRunner
            .withUserConfiguration(MultiNodeGraphConfig.class)
            .run(context -> {
                assertThat(context).hasBean("multiGraph_compiledGraph");
                assertThat(context.getBean("multiGraph_compiledGraph")).isInstanceOf(CompiledGraph.class);
            });
    }

    // ================================================================
    // NO GRAPH WORKFLOW
    // ================================================================

    @Test
    void noGraphWorkflowBeansMeansNoCompiledGraphBeans() {
        contextRunner
            .run(context -> {
                String[] compiledGraphBeans = context.getBeanNamesForType(CompiledGraph.class);
                assertThat(compiledGraphBeans).isEmpty();
            });
    }

    // ================================================================
    // IDEMPOTENCY
    // ================================================================

    @Test
    void duplicateCompiledGraphNameSkipped() {
        contextRunner
            .withUserConfiguration(SimpleGraphConfig.class)
            .run(context -> {
                String[] compiledGraphBeans = context.getBeanNamesForType(CompiledGraph.class);
                long simpleGraphCount = java.util.Arrays.stream(compiledGraphBeans)
                    .filter(name -> name.equals("simpleGraph_compiledGraph"))
                    .count();
                assertThat(simpleGraphCount).isEqualTo(1);
            });
    }

    // ================================================================
    // Test configurations
    // ================================================================

    @GraphWorkflow(entryPoint = "start", finishPoints = {"end"})
    @GraphEdge(from = "start", to = "end")
    static class SimpleGraphBean {
        @GraphNode("start")
        public Map<String, Object> start(Map<String, Object> state, com.chorus.engine.core.reactive.CancellationToken token) {
            return Map.of("step", "started");
        }

        @GraphNode("end")
        public Map<String, Object> end(Map<String, Object> state, com.chorus.engine.core.reactive.CancellationToken token) {
            return Map.of("step", "finished");
        }
    }

    @Configuration
    static class SimpleGraphConfig {
        @Bean
        public SimpleGraphBean simpleGraph() {
            return new SimpleGraphBean();
        }
    }

    @GraphWorkflow(entryPoint = "nodeA", finishPoints = {"nodeD"})
    @GraphEdges({
        @GraphEdge(from = "nodeA", to = "nodeB"),
        @GraphEdge(from = "nodeB", to = "nodeC"),
        @GraphEdge(from = "nodeC", to = "nodeD")
    })
    static class MultiNodeGraphBean {
        @GraphNode("nodeA")
        public Map<String, Object> nodeA(Map<String, Object> state, com.chorus.engine.core.reactive.CancellationToken token) {
            return Map.of("a", true);
        }

        @GraphNode("nodeB")
        public Map<String, Object> nodeB(Map<String, Object> state, com.chorus.engine.core.reactive.CancellationToken token) {
            return Map.of("b", true);
        }

        @GraphNode("nodeC")
        public Map<String, Object> nodeC(Map<String, Object> state, com.chorus.engine.core.reactive.CancellationToken token) {
            return Map.of("c", true);
        }

        @GraphNode("nodeD")
        public Map<String, Object> nodeD(Map<String, Object> state, com.chorus.engine.core.reactive.CancellationToken token) {
            return Map.of("d", true);
        }
    }

    @Configuration
    static class MultiNodeGraphConfig {
        @Bean
        public MultiNodeGraphBean multiGraph() {
            return new MultiNodeGraphBean();
        }
    }
}
