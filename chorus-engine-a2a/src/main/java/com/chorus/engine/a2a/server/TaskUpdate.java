package com.chorus.engine.a2a.server;

import com.chorus.engine.a2a.task.Artifact;
import com.chorus.engine.a2a.task.Message;
import com.chorus.engine.a2a.task.Task;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

public record TaskUpdate(
    @NonNull String taskId,
    Task.@NonNull Status status,
    @Nullable Message message,
    @Nullable Artifact artifact
) {}
