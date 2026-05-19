package com.chorus.engine.mcp.transport;

import com.chorus.engine.mcp.protocol.McpMessage.JsonRpcMessage;
import com.chorus.engine.mcp.protocol.McpMessage.JsonRpcRequest;
import com.chorus.engine.mcp.protocol.McpMessage.JsonRpcResponse;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.CookieHandler;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpClient.Version;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.*;

class HttpSseTransportTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void start_connectsToEndpoint() {
        FakeHttpClient fake = new FakeHttpClient();
        HttpSseTransport transport = new HttpSseTransport(
            URI.create("http://localhost/mcp"), fake, mapper
        );

        transport.start();

        // start() is idempotent
        transport.start();
        assertThatNoException().isThrownBy(transport::start);

        transport.close();
    }

    @Test
    void send_serializesMessage() throws Exception {
        FakeHttpClient fake = new FakeHttpClient()
            .withResponse(req -> new MockHttpResponse<>(req, 200, "{}"));
        HttpSseTransport transport = new HttpSseTransport(
            URI.create("http://localhost/mcp"), fake, mapper
        );

        JsonRpcRequest message = JsonRpcRequest.of(1, "initialize", Map.of("protocolVersion", "2024-11-05"));
        transport.send(message);

        assertThat(fake.requests).hasSize(1);
        HttpRequest captured = fake.requests.get(0);
        assertThat(captured.method()).isEqualTo("POST");
        assertThat(captured.uri().toString()).isEqualTo("http://localhost/mcp");
        assertThat(captured.headers().firstValue("Content-Type")).hasValue("application/json");
    }

    @Test
    void send_forwardsResponseToSubscribers() throws Exception {
        JsonRpcResponse response = JsonRpcResponse.of(1, mapper.valueToTree(Map.of("status", "ok")));
        String body = mapper.writeValueAsString(response);
        FakeHttpClient fake = new FakeHttpClient()
            .withResponse(req -> new MockHttpResponse<>(req, 200, body));
        HttpSseTransport transport = new HttpSseTransport(
            URI.create("http://localhost/mcp"), fake, mapper
        );

        List<JsonRpcMessage> received = new ArrayList<>();
        CountDownLatch latch = new CountDownLatch(1);
        transport.receive().subscribe(new Flow.Subscriber<>() {
            @Override public void onSubscribe(Flow.Subscription s) { s.request(Long.MAX_VALUE); }
            @Override public void onNext(JsonRpcMessage msg) { received.add(msg); latch.countDown(); }
            @Override public void onError(Throwable t) { latch.countDown(); }
            @Override public void onComplete() {}
        });

        JsonRpcRequest message = JsonRpcRequest.of(1, "initialize", Map.of());
        transport.send(message);

        assertThat(latch.await(3, TimeUnit.SECONDS)).isTrue();
        assertThat(received).hasSize(1);
        assertThat(received.get(0)).isInstanceOf(JsonRpcResponse.class);
    }

    @Test
    void receive_parsesSseEvents() throws Exception {
        JsonRpcResponse response = JsonRpcResponse.of(42, mapper.valueToTree(Map.of("ping", true)));
        String sseData = "data: " + mapper.writeValueAsString(response) + "\n\n";
        ByteArrayInputStream stream = new ByteArrayInputStream(sseData.getBytes(StandardCharsets.UTF_8));

        FakeHttpClient fake = new FakeHttpClient()
            .withStreamResponse(req -> new MockHttpResponse<>(req, 200, stream));
        HttpSseTransport transport = new HttpSseTransport(
            URI.create("http://localhost/mcp"), fake, mapper
        );

        List<JsonRpcMessage> received = new ArrayList<>();
        CountDownLatch latch = new CountDownLatch(1);
        transport.receive().subscribe(new Flow.Subscriber<>() {
            @Override public void onSubscribe(Flow.Subscription s) { s.request(Long.MAX_VALUE); }
            @Override public void onNext(JsonRpcMessage msg) { received.add(msg); latch.countDown(); }
            @Override public void onError(Throwable t) { latch.countDown(); }
            @Override public void onComplete() { latch.countDown(); }
        });

        transport.start();

        assertThat(latch.await(3, TimeUnit.SECONDS)).isTrue();
        assertThat(received).hasSize(1);
        assertThat(received.get(0)).isInstanceOf(JsonRpcResponse.class);
        JsonRpcResponse parsed = (JsonRpcResponse) received.get(0);
        assertThat(parsed.id()).isEqualTo(42);

        transport.close();
    }

    @Test
    void send_non200Status_throws() {
        FakeHttpClient fake = new FakeHttpClient()
            .withResponse(req -> new MockHttpResponse<>(req, 400, "Bad Request"));
        HttpSseTransport transport = new HttpSseTransport(
            URI.create("http://localhost/mcp"), fake, mapper
        );

        JsonRpcRequest message = JsonRpcRequest.of(1, "test", Map.of());

        assertThatThrownBy(() -> transport.send(message))
            .isInstanceOf(RuntimeException.class)
            .hasMessageContaining("HTTP error 400");
    }

    @Test
    void send_connectionFailure_throws() {
        FakeHttpClient fake = new FakeHttpClient()
            .withResponse(req -> { throw new java.io.UncheckedIOException(new IOException("Connection refused")); });
        HttpSseTransport transport = new HttpSseTransport(
            URI.create("http://localhost/mcp"), fake, mapper
        );

        JsonRpcRequest message = JsonRpcRequest.of(1, "test", Map.of());

        assertThatThrownBy(() -> transport.send(message))
            .isInstanceOf(RuntimeException.class)
            .hasMessageContaining("Failed to send message")
            .hasCauseInstanceOf(java.io.UncheckedIOException.class);
    }

    @Test
    void constructor_rejectsNulls() {
        URI endpoint = URI.create("http://localhost/mcp");
        HttpClient client = HttpClient.newHttpClient();

        assertThatNullPointerException().isThrownBy(() ->
            new HttpSseTransport(null, client, mapper)
        ).withMessageContaining("endpoint");

        assertThatNullPointerException().isThrownBy(() ->
            new HttpSseTransport(endpoint, null, mapper)
        ).withMessageContaining("httpClient");

        assertThatNullPointerException().isThrownBy(() ->
            new HttpSseTransport(endpoint, client, null)
        ).withMessageContaining("mapper");
    }

    @Test
    void send_rejectsNullMessage() {
        HttpSseTransport transport = new HttpSseTransport(
            URI.create("http://localhost/mcp"), new FakeHttpClient(), mapper
        );

        assertThatNullPointerException().isThrownBy(() -> transport.send(null));
    }

    // ---- Fake HTTP client ----

    private static final class FakeHttpClient extends HttpClient {
        final List<HttpRequest> requests = new ArrayList<>();
        private Function<HttpRequest, HttpResponse<?>> responseFactory;

        FakeHttpClient() {
            this.responseFactory = req -> new MockHttpResponse<>(req, 200, "{}");
        }

        FakeHttpClient withResponse(Function<HttpRequest, HttpResponse<?>> factory) {
            this.responseFactory = factory;
            return this;
        }

        FakeHttpClient withStreamResponse(Function<HttpRequest, HttpResponse<?>> factory) {
            this.responseFactory = factory;
            return this;
        }

        @Override
        @SuppressWarnings("unchecked")
        public <T> HttpResponse<T> send(HttpRequest request, HttpResponse.BodyHandler<T> responseBodyHandler)
                throws IOException, InterruptedException {
            requests.add(request);
            Object result = responseFactory.apply(request);
            if (result instanceof Exception e) {
                if (e instanceof IOException ioe) throw ioe;
                if (e instanceof RuntimeException re) throw re;
                throw new RuntimeException(e);
            }
            return (HttpResponse<T>) result;
        }

        @Override public <T> CompletableFuture<HttpResponse<T>> sendAsync(HttpRequest request, HttpResponse.BodyHandler<T> responseBodyHandler) {
            throw new UnsupportedOperationException();
        }
        @Override public <T> CompletableFuture<HttpResponse<T>> sendAsync(HttpRequest request, HttpResponse.BodyHandler<T> responseBodyHandler, HttpResponse.PushPromiseHandler<T> pushPromiseHandler) {
            throw new UnsupportedOperationException();
        }
        @Override public Optional<CookieHandler> cookieHandler() { return Optional.empty(); }
        @Override public Optional<java.time.Duration> connectTimeout() { return Optional.empty(); }
        @Override public Redirect followRedirects() { return Redirect.NEVER; }
        @Override public Optional<java.net.ProxySelector> proxy() { return Optional.empty(); }
        @Override public javax.net.ssl.SSLContext sslContext() { return null; }
        @Override public javax.net.ssl.SSLParameters sslParameters() { return null; }
        @Override public Optional<java.net.Authenticator> authenticator() { return Optional.empty(); }
        @Override public Version version() { return Version.HTTP_2; }
        @Override public Optional<java.util.concurrent.Executor> executor() { return Optional.empty(); }
        @Override public java.net.http.WebSocket.Builder newWebSocketBuilder() { throw new UnsupportedOperationException(); }
    }

    private record MockHttpResponse<T>(HttpRequest request, int statusCode, T body) implements HttpResponse<T> {
        @Override public HttpRequest request() { return request; }
        @Override public Optional<HttpResponse<T>> previousResponse() { return Optional.empty(); }
        @Override public HttpHeaders headers() { return HttpHeaders.of(Map.of(), (a, b) -> true); }
        @Override public T body() { return body; }
        @Override public URI uri() { return request != null ? request.uri() : URI.create("http://localhost"); }
        @Override public Version version() { return Version.HTTP_2; }
        @Override public Optional<javax.net.ssl.SSLSession> sslSession() { return Optional.empty(); }
    }
}
