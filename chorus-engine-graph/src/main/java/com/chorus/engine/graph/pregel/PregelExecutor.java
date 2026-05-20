package com.chorus.engine.graph.pregel;

import com.chorus.engine.core.checkpoint.Checkpointer;
import com.chorus.engine.core.reactive.CancellationToken;
import com.chorus.engine.core.result.Result;
import com.chorus.engine.graph.checkpoint.GraphCheckpointer;
import com.chorus.engine.graph.state.GraphEvent;
import com.chorus.engine.graph.state.Node;
import com.chorus.engine.graph.state.StateGraph;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

import java.util.*;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.StructuredTaskScope;
import java.util.concurrent.StructuredTaskScope.Joiner;
import java.util.concurrent.Flow;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

/**
 * Pregel-inspired execution engine for {@link StateGraph}.
 *
 * <p>Executes nodes in super-steps. All nodes scheduled for a given super-step
 * run in parallel via {@link StructuredTaskScope}. After each super-step,
 * outputs are merged and the next set of nodes is computed from outgoing edges.
 *
 * <p>Supports:
 * <ul>
 *   <li>Conditional branching via {@link com.chorus.engine.graph.state.Router}</li>
 *   <li>Cycles (nodes can route back to earlier nodes)</li>
 *   <li>Checkpoint persistence after every super-step</li>
 *   <li>Human-in-the-loop via {@code interruptBefore} / {@code interruptAfter}</li>
 *   <li>Max-iterations guard (default 100)</li>
 * </ul>
 */
public final class PregelExecutor<S> {

    private final StateGraph<S> graph;
    private final GraphCheckpointer<S> checkpointer;
    private final Set<String> interruptBefore;
    private final Set<String> interruptAfter;
    private final int maxIterations;

    public PregelExecutor(
        @NonNull StateGraph<S> graph,
        @NonNull GraphCheckpointer<S> checkpointer,
        @NonNull Set<String> interruptBefore,
        @NonNull Set<String> interruptAfter
    ) {
        this(graph, checkpointer, interruptBefore, interruptAfter, 100);
    }

    public PregelExecutor(
        @NonNull StateGraph<S> graph,
        @NonNull GraphCheckpointer<S> checkpointer,
        @NonNull Set<String> interruptBefore,
        @NonNull Set<String> interruptAfter,
        int maxIterations
    ) {
        this.graph = graph;
        this.checkpointer = checkpointer;
        this.interruptBefore = Set.copyOf(interruptBefore);
        this.interruptAfter = Set.copyOf(interruptAfter);
        this.maxIterations = maxIterations;
    }

    /**
     * Execute the graph synchronously and return the final state.
     *
     * @param initialState the starting state
     * @param threadId     the execution thread identifier
     * @param token        cancellation token
     * @return the final state after the graph completes or is interrupted
     */
    public @NonNull S invoke(@NonNull S initialState, @NonNull String threadId, @NonNull CancellationToken token) {
        AtomicReference<S> resultRef = new AtomicReference<>();
        runInternal(initialState, threadId, token, Set.of(graph.entryPoint()), 0, false, evt -> {
            if (evt instanceof GraphEvent.GraphEnd<S> end) {
                resultRef.set(end.finalState());
            } else if (evt instanceof GraphEvent.CheckpointSaved<S> saved) {
                resultRef.set(saved.state());
            }
        });
        S result = resultRef.get();
        if (result == null) {
            throw new IllegalStateException("Graph produced no state for thread: " + threadId);
        }
        return result;
    }

    /**
     * Resume execution from the latest checkpoint.
     *
     * @param threadId the execution thread identifier
     * @param token    cancellation token
     * @return the final or interrupted state
     */
    public @NonNull S resume(@NonNull String threadId, @NonNull CancellationToken token) {
        Result<GraphCheckpointer.Checkpoint<S>, Checkpointer.CheckpointError> loaded = checkpointer.loadLatest(threadId);
        if (loaded.isErr()) {
            throw new IllegalStateException("No checkpoint to resume for thread: " + threadId);
        }
        GraphCheckpointer.Checkpoint<S> checkpoint = loaded.unwrap();
        if (checkpoint.nextNodes().isEmpty()) {
            throw new IllegalStateException("Graph already completed for thread: " + threadId);
        }
        AtomicReference<S> resultRef = new AtomicReference<>();
        runInternal(checkpoint.state(), threadId, token, new LinkedHashSet<>(checkpoint.nextNodes()), checkpoint.sequence(), true, evt -> {
            if (evt instanceof GraphEvent.GraphEnd<S> end) {
                resultRef.set(end.finalState());
            } else if (evt instanceof GraphEvent.CheckpointSaved<S> saved) {
                resultRef.set(saved.state());
            }
        });
        S result = resultRef.get();
        if (result == null) {
            throw new IllegalStateException("Graph produced no state for thread: " + threadId);
        }
        return result;
    }

    /**
     * Execute the graph and stream events.
     */
    public Flow.@NonNull Publisher<GraphEvent<S>> stream(
        @NonNull S initialState, @NonNull String threadId, @NonNull CancellationToken token
    ) {
        return subscriber -> {
            try {
                subscriber.onSubscribe(new Flow.Subscription() {
                    @Override public void request(long n) {}
                    @Override public void cancel() { token.cancel("Subscriber cancelled"); }
                });
                runInternal(initialState, threadId, token, Set.of(graph.entryPoint()), 0, false, subscriber::onNext);
                subscriber.onComplete();
            } catch (Exception e) {
                subscriber.onError(e);
            }
        };
    }

    /**
     * Resume and stream events from the latest checkpoint.
     */
    public Flow.@NonNull Publisher<GraphEvent<S>> streamResume(
        @NonNull String threadId, @NonNull CancellationToken token
    ) {
        return subscriber -> {
            try {
                subscriber.onSubscribe(new Flow.Subscription() {
                    @Override public void request(long n) {}
                    @Override public void cancel() { token.cancel("Subscriber cancelled"); }
                });
                Result<GraphCheckpointer.Checkpoint<S>, Checkpointer.CheckpointError> loaded = checkpointer.loadLatest(threadId);
                if (loaded.isErr()) {
                    subscriber.onError(new IllegalStateException("No checkpoint to resume"));
                    return;
                }
                GraphCheckpointer.Checkpoint<S> checkpoint = loaded.unwrap();
                runInternal(checkpoint.state(), threadId, token,
                    new LinkedHashSet<>(checkpoint.nextNodes()), checkpoint.sequence(), true, subscriber::onNext);
                subscriber.onComplete();
            } catch (Exception e) {
                subscriber.onError(e);
            }
        };
    }

    // ---- internal execution ----

    private void runInternal(
        @NonNull S initialState,
        @NonNull String threadId,
        @NonNull CancellationToken token,
        @NonNull Set<String> startingNodes,
        long startingSequence,
        boolean isResume,
        @NonNull Consumer<GraphEvent<S>> eventSink
    ) {
        S currentState = initialState;
        Set<String> nextNodes = new LinkedHashSet<>(startingNodes);
        long sequence = startingSequence;

        while (!nextNodes.isEmpty() && !token.isCancelled()) {
            if (sequence >= maxIterations) {
                throw new IllegalStateException("Max iterations exceeded: " + maxIterations);
            }
            token.throwIfCancelled();

            // Terminal sentinel
            if (nextNodes.contains("__end__")) {
                if (nextNodes.size() == 1) {
                    eventSink.accept(new GraphEvent.GraphEnd<>(currentState));
                    return;
                }
                nextNodes.remove("__end__");
                if (nextNodes.isEmpty()) {
                    eventSink.accept(new GraphEvent.GraphEnd<>(currentState));
                    return;
                }
            }

            // Validate all scheduled nodes exist
            for (String nodeName : nextNodes) {
                if (!graph.nodes().containsKey(nodeName)) {
                    throw new IllegalStateException("Scheduled node does not exist: " + nodeName);
                }
            }

            // Interrupt-before check (skipped on first super-step after resume)
            if (!isResume) {
                Set<String> toInterrupt = new LinkedHashSet<>();
                for (String node : nextNodes) {
                    if (interruptBefore.contains(node)) {
                        toInterrupt.add(node);
                    }
                }
                if (!toInterrupt.isEmpty()) {
                    checkpointer.save(threadId, sequence, currentState, new ArrayList<>(nextNodes));
                    eventSink.accept(new GraphEvent.CheckpointSaved<>(threadId, sequence, currentState));
                    return;
                }
            }

            // Execute super-step
            Map<String, S> outputs = runSuperStep(nextNodes, currentState, token, eventSink);

            // Merge outputs in deterministic node-name order
            List<String> sortedNodes = new ArrayList<>(nextNodes);
            Collections.sort(sortedNodes);
            for (String nodeName : sortedNodes) {
                S output = outputs.get(nodeName);
                if (output != null) {
                    currentState = graph.updater().apply(currentState, output);
                }
            }

            // Compute next nodes before interrupt-after check so pending nodes are preserved
            Set<String> computedNext = computeNextNodes(nextNodes, currentState, token, eventSink);

            // Advance sequence before checkpoints so both interrupt-after and normal
            // checkpoints use a consistent post-step sequence number.
            Set<String> executedNodes = new LinkedHashSet<>(nextNodes);
            nextNodes = computedNext;
            sequence++;
            isResume = false;

            // Interrupt-after check
            for (String node : executedNodes) {
                if (interruptAfter.contains(node)) {
                    checkpointer.save(threadId, sequence, currentState, new ArrayList<>(nextNodes));
                    eventSink.accept(new GraphEvent.CheckpointSaved<>(threadId, sequence, currentState));
                    return;
                }
            }

            // Checkpoint
            checkpointer.save(threadId, sequence, currentState, new ArrayList<>(nextNodes));
            eventSink.accept(new GraphEvent.CheckpointSaved<>(threadId, sequence, currentState));
        }

        if (token.isCancelled()) {
            throw new CancellationException(token.reason());
        }

        eventSink.accept(new GraphEvent.GraphEnd<>(currentState));
    }

    private Map<String, S> runSuperStep(
        @NonNull Set<String> nodeNames,
        @NonNull S state,
        @NonNull CancellationToken token,
        @NonNull Consumer<GraphEvent<S>> eventSink
    ) {
        Map<String, S> outputs = Collections.synchronizedMap(new LinkedHashMap<>());

        try (var scope = StructuredTaskScope.open(Joiner.awaitAllSuccessfulOrThrow())) {
            for (String nodeName : nodeNames) {
                Node<S> node = graph.nodes().get(nodeName);
                scope.fork(() -> {
                    eventSink.accept(new GraphEvent.NodeStart<>(nodeName, state));
                    S output = node.invoke(state, token);
                    outputs.put(nodeName, output);
                    eventSink.accept(new GraphEvent.NodeEnd<>(nodeName, state, output));
                    return output;
                });
            }
            scope.join();
        } catch (Exception e) {
            Throwable cause = e instanceof ExecutionException ? e.getCause() : e;
            String joined = String.join(",", nodeNames);
            eventSink.accept(new GraphEvent.GraphError<>(joined, cause));
            if (cause instanceof RuntimeException re) {
                throw re;
            }
            throw new RuntimeException(cause);
        }

        return outputs;
    }

    private Set<String> computeNextNodes(
        @NonNull Set<String> currentNodes,
        @NonNull S state,
        @NonNull CancellationToken token,
        @NonNull Consumer<GraphEvent<S>> eventSink
    ) {
        Set<String> next = new LinkedHashSet<>();
        for (String node : currentNodes) {
            boolean hasEdge = false;

            // Unconditional edges
            List<String> unconditional = graph.edges().get(node);
            if (unconditional != null && !unconditional.isEmpty()) {
                hasEdge = true;
                for (String target : unconditional) {
                    next.add(target);
                    eventSink.accept(new GraphEvent.EdgeTransition<>(node, target));
                }
            }

            // Conditional edge
            StateGraph.ConditionalEdge<S> conditional = graph.conditionalEdges().get(node);
            if (conditional != null) {
                if (hasEdge) {
                    throw new IllegalStateException(
                        "Node " + node + " has both unconditional and conditional outgoing edges");
                }
                hasEdge = true;
                String dest = conditional.router().route(state, token);
                String target = conditional.destinations().get(dest);
                if (target == null) {
                    throw new IllegalStateException(
                        "Router for node " + node + " returned unknown destination: " + dest);
                }
                next.add(target);
                eventSink.accept(new GraphEvent.EdgeTransition<>(node, target));
            }

            if (!hasEdge) {
                // Terminal node — no outgoing edges
            }
        }
        return next;
    }
}
