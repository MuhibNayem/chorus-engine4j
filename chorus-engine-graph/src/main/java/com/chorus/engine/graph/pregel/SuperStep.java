package com.chorus.engine.graph.pregel;

import org.jspecify.annotations.NonNull;

import java.util.Set;

/**
 * Represents one super-step of Pregel execution.
 *
 * <p>In each super-step, all {@code nodesToRun} execute in parallel,
 * their outputs are merged, and the next super-step is computed from
 * outgoing edges.
 *
 * @param nodesToRun the set of node names scheduled for this step
 * @param iteration  the super-step index (0-based)
 */
public record SuperStep(@NonNull Set<String> nodesToRun, long iteration) {}
