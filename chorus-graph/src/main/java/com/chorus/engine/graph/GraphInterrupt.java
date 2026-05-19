package com.chorus.engine.graph;

/**
 * Exception thrown by a graph node to pause execution for human-in-the-loop.
 * The graph checkpoints state and yields an interrupt event.
 * Execution can be resumed later with a {@link Command}.
 */
public class GraphInterrupt extends RuntimeException {

    private final String nodeName;
    private final Object value;

    public GraphInterrupt(String nodeName, Object value) {
        super("Graph interrupted at node: " + nodeName);
        this.nodeName = nodeName;
        this.value = value;
    }

    public String nodeName() { return nodeName; }
    public Object value() { return value; }
}
