package com.chorus.engine.mcp;

import com.chorus.engine.mcp.protocol.McpMessage.JsonRpcMessage;
import com.chorus.engine.mcp.transport.McpTransport;

import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Flow;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * In-memory bidirectional transport for testing.
 *
 * <p>Messages injected via {@link #inject(JsonRpcMessage)} are dispatched
 * synchronously to all current subscribers. Messages sent via
 * {@link #send(JsonRpcMessage)} are queued and can be retrieved with
 * {@link #pollOutbound()}.
 */
public final class FakeTransport implements McpTransport {

    private final BlockingQueue<JsonRpcMessage> outbound = new LinkedBlockingQueue<>();
    private final CopyOnWriteArrayList<Flow.Subscriber<? super JsonRpcMessage>> subscribers = new CopyOnWriteArrayList<>();
    private final AtomicBoolean started = new AtomicBoolean(false);
    private final AtomicBoolean closed = new AtomicBoolean(false);

    @Override
    public void start() {
        started.set(true);
    }

    @Override
    public void send(JsonRpcMessage message) {
        if (closed.get()) {
            throw new IllegalStateException("Transport is closed");
        }
        outbound.add(message);
    }

    @Override
    public Flow.Publisher<JsonRpcMessage> receive() {
        return new FakePublisher();
    }

    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        for (var sub : subscribers) {
            sub.onComplete();
        }
    }

    /**
     * Inject a message into this transport's receive stream (simulates peer sending).
     */
    public void inject(JsonRpcMessage message) {
        for (var sub : subscribers) {
            sub.onNext(message);
        }
    }

    /**
     * Poll the next outbound message (what this transport has sent).
     */
    public JsonRpcMessage pollOutbound() throws InterruptedException {
        return outbound.take();
    }

    private final class FakePublisher implements Flow.Publisher<JsonRpcMessage> {
        @Override
        public void subscribe(Flow.Subscriber<? super JsonRpcMessage> subscriber) {
            subscriber.onSubscribe(new Flow.Subscription() {
                @Override
                public void request(long n) {}

                @Override
                public void cancel() {
                    subscribers.remove(subscriber);
                }
            });
            subscribers.add(subscriber);
        }
    }
}
