package com.chorus.engine.llm.testutil;

import java.net.http.HttpRequest;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;

public final class HttpRequestBodyExtractor {

    private HttpRequestBodyExtractor() {}

    public static String extract(HttpRequest request) {
        HttpRequest.BodyPublisher publisher = request.bodyPublisher()
            .orElseThrow(() -> new IllegalArgumentException("Request has no body publisher"));

        List<ByteBuffer> buffers = new ArrayList<>();
        CountDownLatch latch = new CountDownLatch(1);

        publisher.subscribe(new Flow.Subscriber<>() {
            @Override
            public void onSubscribe(Flow.Subscription subscription) {
                subscription.request(Long.MAX_VALUE);
            }

            @Override
            public void onNext(ByteBuffer item) {
                buffers.add(item);
            }

            @Override
            public void onError(Throwable throwable) {
                latch.countDown();
            }

            @Override
            public void onComplete() {
                latch.countDown();
            }
        });

        try {
            if (!latch.await(1, TimeUnit.SECONDS)) {
                throw new IllegalStateException("Timed out reading request body");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while reading request body");
        }

        int total = buffers.stream().mapToInt(ByteBuffer::remaining).sum();
        byte[] bytes = new byte[total];
        int pos = 0;
        for (ByteBuffer buf : buffers) {
            int len = buf.remaining();
            buf.get(bytes, pos, len);
            pos += len;
        }
        return new String(bytes, StandardCharsets.UTF_8);
    }
}
