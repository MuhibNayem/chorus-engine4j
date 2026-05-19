package com.chorus.engine.core.trace;

/**
 * ThreadLocal carrier for TraceContext that propagates through the agent loop.
 * Each thread of execution gets its own isolated trace context.
 *
 * <p>Usage:</p>
 * <pre>
 * TraceCarrier.set(TraceContext.createRoot());
 * try {
 *     agentLoop.run().subscribe(...);
 * } finally {
 *     TraceCarrier.clear();
 * }
 * </pre>
 */
public final class TraceCarrier {

    private static final ThreadLocal<TraceContext> CONTEXT = new ThreadLocal<>();

    private TraceCarrier() {}

    public static void set(TraceContext ctx) {
        CONTEXT.set(ctx);
    }

    public static TraceContext get() {
        return CONTEXT.get();
    }

    public static void clear() {
        CONTEXT.remove();
    }

    public static boolean hasContext() {
        return CONTEXT.get() != null;
    }

    /**
     * Get or create a root trace context for the current thread.
     */
    public static TraceContext getOrCreate() {
        TraceContext ctx = CONTEXT.get();
        if (ctx == null) {
            ctx = TraceContext.createRoot();
            CONTEXT.set(ctx);
        }
        return ctx;
    }
}
