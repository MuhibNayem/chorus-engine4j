package com.chorus.engine.graph;

import java.util.Map;

/**
 * Events emitted by {@link CompiledGraph#stream}.
 */
public sealed interface GraphEvent permits
    GraphEvent.NodeStartEvent,
    GraphEvent.NodeEndEvent,
    GraphEvent.StateEvent,
    GraphEvent.InterruptEvent,
    GraphEvent.EndEvent,
    GraphEvent.ErrorEvent,
    GraphEvent.TimeoutEvent,
    GraphEvent.DeadlockEvent {

    record NodeStartEvent(String node, String threadId) implements GraphEvent {}
    record NodeEndEvent(String node, String threadId, long durationMs) implements GraphEvent {}
    record StateEvent(String node, Map<String, Object> state) implements GraphEvent {}
    record InterruptEvent(String node, Object value) implements GraphEvent {}
    record EndEvent(Map<String, Object> finalState) implements GraphEvent {}
    record ErrorEvent(String node, String message, boolean fatal) implements GraphEvent {}
    record TimeoutEvent(String node, long timeoutMs) implements GraphEvent {}
    record DeadlockEvent(String message) implements GraphEvent {}
}
