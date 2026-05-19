package com.chorus.engine.graph;

import java.util.Map;

/**
 * Dynamic fan-out primitive. A node can return {@code Send} objects
 * to trigger additional nodes with custom state slices.
 */
public record Send(
    String node,
    Map<String, Object> args
) {}
