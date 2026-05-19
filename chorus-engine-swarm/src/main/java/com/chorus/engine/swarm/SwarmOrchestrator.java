package com.chorus.engine.swarm;

import com.chorus.engine.core.context.Message;
import com.chorus.engine.core.reactive.CancellationToken;
import org.jspecify.annotations.NonNull;

import java.util.List;
import java.util.concurrent.Flow;

/**
 * Base interface for all swarm orchestrators.
 */
public interface SwarmOrchestrator {

    /**
     * Run the swarm session until completion, error, or cancellation.
     *
     * @param session the mutable session state
     * @param token   cooperative cancellation token
     * @return publisher of swarm lifecycle events
     */
    Flow.@NonNull Publisher<SwarmEvent> run(
        @NonNull SwarmSession session,
        @NonNull CancellationToken token
    );

    /**
     * Factory method to create a fresh session.
     */
    @NonNull SwarmSession createSession(
        @NonNull String initialAgent,
        @NonNull List<Message> messages
    );
}
