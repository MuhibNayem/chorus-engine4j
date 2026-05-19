package com.chorus.engine.core.loop;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * Async side-channel message queue. Messages injected via {@code [/btw]} are
 * drained into the conversation history each round.
 */
public class BtwQueue {

    private final ConcurrentLinkedQueue<String> queue = new ConcurrentLinkedQueue<>();

    public void push(String text) {
        queue.add(text);
    }

    public List<String> drain() {
        List<String> result = new ArrayList<>();
        String item;
        while ((item = queue.poll()) != null) {
            result.add(item);
        }
        return result;
    }

    public boolean isEmpty() {
        return queue.isEmpty();
    }
}
