package com.chorus.engine.core.delegate;

import com.chorus.engine.core.event.ChatMessage;
import com.chorus.engine.core.trace.TraceContext;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * An isolated execution context for a sub-agent delegation.
 * Maintains its own message history, thread ID, checkpoint namespace,
 * and distributed trace context separate from the parent agent.
 */
public class IsolatedContext {

    private final String threadId;
    private final String parentThreadId;
    private final List<ChatMessage> messages;
    private final String checkpointNamespace;
    private TraceContext traceContext;

    public IsolatedContext(String parentThreadId) {
        this(parentThreadId, UUID.randomUUID().toString());
    }

    public IsolatedContext(String parentThreadId, String threadId) {
        this.parentThreadId = parentThreadId;
        this.threadId = threadId;
        this.messages = new CopyOnWriteArrayList<>();
        this.checkpointNamespace = "subagent-" + threadId;
    }

    public String threadId() { return threadId; }
    public String parentThreadId() { return parentThreadId; }
    public String checkpointNamespace() { return checkpointNamespace; }

    public List<ChatMessage> messages() { return List.copyOf(messages); }

    public void addMessage(ChatMessage msg) {
        messages.add(msg);
    }

    public void addMessages(List<ChatMessage> msgs) {
        messages.addAll(msgs);
    }

    public TraceContext traceContext() { return traceContext; }

    public void setTraceContext(TraceContext traceContext) {
        this.traceContext = traceContext;
    }

    public IsolatedContext fork() {
        IsolatedContext forked = new IsolatedContext(parentThreadId, UUID.randomUUID().toString());
        forked.addMessages(this.messages);
        if (this.traceContext != null) {
            forked.setTraceContext(this.traceContext.createChild(
                TraceContext.createRoot().parentId()));
        }
        return forked;
    }

    public static IsolatedContext root() {
        return new IsolatedContext("root", "root");
    }
}
