package com.chorus.engine.graph;

import java.util.Map;

/**
 * Command to resume a graph after an interrupt or to dynamically update state.
 */
public record Command(
    String gotoNode,
    Map<String, Object> update
) {

    public static Command resume() {
        return new Command(null, Map.of());
    }

    public static Command gotoNode(String node) {
        return new Command(node, Map.of());
    }

    public static Command update(Map<String, Object> state) {
        return new Command(null, state);
    }
}
