package com.chorus.engine.graph.checkpoint;

import com.chorus.engine.core.checkpoint.AgentState;
import com.chorus.engine.core.checkpoint.Checkpointer;
import com.chorus.engine.core.result.Result;
import org.jspecify.annotations.NonNull;

import java.io.*;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/**
 * Checkpointing abstraction tailored for graph execution state.
 *
 * <p>Wraps the low-level {@link Checkpointer} and handles serialization of
 * the generic state type {@code S} into {@link AgentState}.
 *
 * @param <S> the graph state type
 */
public interface GraphCheckpointer<S> {

    /**
     * Persist a checkpoint.
     *
     * @param threadId  the execution thread / run identifier
     * @param sequence  monotonically increasing sequence number
     * @param state     the current graph state
     * @param nextNodes nodes scheduled for the next super-step
     * @return ok or checkpoint error
     */
    @NonNull Result<Void, Checkpointer.CheckpointError> save(
        @NonNull String threadId,
        long sequence,
        @NonNull S state,
        @NonNull List<String> nextNodes
    );

    /**
     * Load the most recent checkpoint for a thread.
     *
     * @param threadId the execution thread / run identifier
     * @return the checkpoint or an error
     */
    @NonNull Result<Checkpoint<S>, Checkpointer.CheckpointError> loadLatest(@NonNull String threadId);

    /**
     * A deserialized checkpoint.
     *
     * @param state     the graph state at this checkpoint
     * @param nextNodes pending nodes for the next super-step
     * @param sequence  the checkpoint sequence number
     */
    record Checkpoint<S>(@NonNull S state, @NonNull List<String> nextNodes, long sequence) {}

    /**
     * Create a checkpointer that uses Java serialization.
     * Suitable when {@code S} implements {@link Serializable}.
     */
    static <S extends Serializable> @NonNull GraphCheckpointer<S> of(@NonNull Checkpointer delegate) {
        return new ByteArrayGraphCheckpointer<>(delegate, serialize(), deserialize());
    }

    /**
     * Create a checkpointer optimized for {@code Map<String, Object>} state.
     */
    static @NonNull GraphCheckpointer<Map<String, Object>> ofMap(@NonNull Checkpointer delegate) {
        return new ByteArrayGraphCheckpointer<>(delegate, serialize(), deserialize());
    }

    /**
     * Create a checkpointer with custom serializer / deserializer functions.
     */
    static <S> @NonNull GraphCheckpointer<S> of(
        @NonNull Checkpointer delegate,
        @NonNull Function<S, byte[]> serializer,
        @NonNull Function<byte[], S> deserializer
    ) {
        return new ByteArrayGraphCheckpointer<>(delegate, serializer, deserializer);
    }

    /**
     * No-op checkpointer for graphs that do not require persistence.
     */
    static <S> @NonNull GraphCheckpointer<S> noop() {
        return new GraphCheckpointer<>() {
            @Override
            public @NonNull Result<Void, Checkpointer.CheckpointError> save(
                @NonNull String threadId, long sequence, @NonNull S state, @NonNull List<String> nextNodes
            ) {
                return new Result.Ok<>(null);
            }

            @Override
            public @NonNull Result<Checkpoint<S>, Checkpointer.CheckpointError> loadLatest(@NonNull String threadId) {
                return Result.err(Checkpointer.CheckpointError.of("NOOP", "No checkpointer configured"));
            }
        };
    }

    // ---- internal helpers ----

    final class ByteArrayGraphCheckpointer<S> implements GraphCheckpointer<S> {
        private final Checkpointer delegate;
        private final Function<S, byte[]> serializer;
        private final Function<byte[], S> deserializer;

        ByteArrayGraphCheckpointer(
            Checkpointer delegate,
            Function<S, byte[]> serializer,
            Function<byte[], S> deserializer
        ) {
            this.delegate = delegate;
            this.serializer = serializer;
            this.deserializer = deserializer;
        }

        @Override
        public @NonNull Result<Void, Checkpointer.CheckpointError> save(
            @NonNull String threadId, long sequence, @NonNull S state, @NonNull List<String> nextNodes
        ) {
            byte[] bytes = serializer.apply(state);
            Map<String, Object> context = Map.of(
                "_state", bytes,
                "_nextNodes", List.copyOf(nextNodes),
                "_sequence", sequence
            );
            AgentState agentState = new AgentState(threadId, sequence, List.of(), context, Map.of());
            return delegate.save(threadId, sequence, agentState);
        }

        @SuppressWarnings("unchecked")
        @Override
        public @NonNull Result<Checkpoint<S>, Checkpointer.CheckpointError> loadLatest(@NonNull String threadId) {
            return delegate.loadLatest(threadId).flatMap(agentState -> {
                Map<String, Object> context = agentState.context();
                byte[] bytes = (byte[]) context.get("_state");
                if (bytes == null) {
                    return Result.err(Checkpointer.CheckpointError.of("CORRUPT",
                        "Checkpoint missing _state field for thread: " + threadId));
                }
                List<String> nextNodes;
                try {
                    nextNodes = (List<String>) context.getOrDefault("_nextNodes", List.of());
                } catch (ClassCastException e) {
                    return Result.err(Checkpointer.CheckpointError.of("CORRUPT",
                        "Checkpoint has malformed _nextNodes field for thread: " + threadId));
                }
                long sequence = ((Number) context.getOrDefault("_sequence", 0L)).longValue();
                S state = deserializer.apply(bytes);
                return Result.ok(new Checkpoint<>(state, nextNodes, sequence));
            });
        }
    }

    private static <T> Function<T, byte[]> serialize() {
        return obj -> {
            try (ByteArrayOutputStream baos = new ByteArrayOutputStream();
                 ObjectOutputStream oos = new ObjectOutputStream(baos)) {
                oos.writeObject(obj);
                return baos.toByteArray();
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        };
    }

    @SuppressWarnings("unchecked")
    private static <T> Function<byte[], T> deserialize() {
        return bytes -> {
            try (ByteArrayInputStream bais = new ByteArrayInputStream(bytes);
                 ObjectInputStream ois = new ObjectInputStream(bais)) {
                return (T) ois.readObject();
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            } catch (ClassNotFoundException e) {
                throw new RuntimeException(e);
            }
        };
    }
}
