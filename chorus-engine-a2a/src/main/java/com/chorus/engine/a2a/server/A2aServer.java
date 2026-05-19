package com.chorus.engine.a2a.server;

import com.chorus.engine.a2a.card.AgentCard;
import com.chorus.engine.a2a.task.Task;
import org.jspecify.annotations.NonNull;

import java.util.concurrent.Flow;

public interface A2aServer {
    @NonNull AgentCard getAgentCard();

    @NonNull Task onSendTask(@NonNull Task task);

    @NonNull Task onGetTask(@NonNull String taskId);

    @NonNull Task onCancelTask(@NonNull String taskId);

    Flow.@NonNull Publisher<TaskUpdate> onSubscribeTask(@NonNull String taskId);
}
