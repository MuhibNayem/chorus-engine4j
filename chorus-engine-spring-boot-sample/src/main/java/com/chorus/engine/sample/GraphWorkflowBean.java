package com.chorus.engine.sample;

import com.chorus.engine.annotation.*;
import org.springframework.stereotype.Component;
import java.util.Map;

@GraphWorkflow(entryPoint = "plan", finishPoints = {"done"})
@GraphEdges({
        @GraphEdge(from = "plan", to = "research"),
        @GraphEdge(from = "research", to = "implement"),
        @GraphEdge(from = "implement", to = "test"),
        @GraphEdge(from = "test", to = "done"),
})
@Component
class CodeWorkflowBean {

    @GraphNode("plan")
    Map<String, Object> plan(Map<String, Object> state) {
        System.out.println("  [graph:plan] Planning task...");
        state.put("phase", "planning");
        return state;
    }

    @GraphNode("research")
    Map<String, Object> research(Map<String, Object> state) {
        System.out.println("  [graph:research] Researching codebase...");
        state.put("phase", "research");
        return state;
    }

    @GraphNode("implement")
    Map<String, Object> implement(Map<String, Object> state) {
        System.out.println("  [graph:implement] Implementing changes...");
        state.put("phase", "implementation");
        return state;
    }

    @GraphNode("test")
    Map<String, Object> test(Map<String, Object> state) {
        System.out.println("  [graph:test] Running tests...");
        state.put("phase", "testing");
        return state;
    }

    @GraphNode("done")
    Map<String, Object> done(Map<String, Object> state) {
        System.out.println("  [graph:done] Complete.");
        state.put("phase", "complete");
        return state;
    }
}
