package com.chorus.engine.rag.store;

import java.io.IOException;
import java.net.Authenticator;
import java.net.CookieHandler;
import java.net.ProxySelector;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpResponse.BodyHandlers;
import java.net.http.HttpHeaders;
import java.net.http.WebSocket;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executor;
import java.util.concurrent.Flow;
import java.util.function.Function;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLSession;

/**
 * Minimal mock HttpClient for testing vector store adapters.
 */
final class MockHttpClient extends HttpClient {

    final CopyOnWriteArrayList<HttpRequest> requests = new CopyOnWriteArrayList<>();
    Function<HttpRequest, HttpResponse<String>> responseFactory;

    MockHttpClient() {
        this.responseFactory = req -> new MockHttpResponse<>(req, 200, "{}");
    }

    MockHttpClient withResponse(int statusCode, String body) {
        this.responseFactory = req -> new MockHttpResponse<>(req, statusCode, body);
        return this;
    }

    MockHttpClient withResponseFactory(Function<HttpRequest, HttpResponse<String>> factory) {
        this.responseFactory = factory;
        return this;
    }

    @Override
    public <T> HttpResponse<T> send(HttpRequest request, HttpResponse.BodyHandler<T> responseBodyHandler) throws IOException, InterruptedException {
        requests.add(request);
        @SuppressWarnings("unchecked")
        HttpResponse<T> response = (HttpResponse<T>) responseFactory.apply(request);
        return response;
    }

    @Override
    public <T> CompletableFuture<HttpResponse<T>> sendAsync(HttpRequest request, HttpResponse.BodyHandler<T> responseBodyHandler) {
        throw new UnsupportedOperationException();
    }

    @Override
    public <T> CompletableFuture<HttpResponse<T>> sendAsync(HttpRequest request, HttpResponse.BodyHandler<T> responseBodyHandler, HttpResponse.PushPromiseHandler<T> pushPromiseHandler) {
        throw new UnsupportedOperationException();
    }

    @Override public Optional<CookieHandler> cookieHandler() { return Optional.empty(); }
    @Override public Optional<Duration> connectTimeout() { return Optional.empty(); }
    @Override public Redirect followRedirects() { return Redirect.NEVER; }
    @Override public Optional<ProxySelector> proxy() { return Optional.empty(); }
    @Override public SSLContext sslContext() { return null; }
    @Override public SSLParameters sslParameters() { return null; }
    @Override public Optional<Authenticator> authenticator() { return Optional.empty(); }
    @Override public HttpClient.Version version() { return HttpClient.Version.HTTP_2; }
    @Override public Optional<Executor> executor() { return Optional.empty(); }
    @Override public WebSocket.Builder newWebSocketBuilder() { throw new UnsupportedOperationException(); }
}

/**
 * Minimal mock HttpResponse for testing.
 */
record MockHttpResponse<T>(HttpRequest request, int statusCode, T body) implements HttpResponse<T> {

    @Override public HttpRequest request() { return request; }
    @Override public Optional<HttpResponse<T>> previousResponse() { return Optional.empty(); }
    @Override public HttpHeaders headers() {
        return HttpHeaders.of(Map.of(), (a, b) -> true);
    }
    @Override public T body() { return body; }
    @Override public URI uri() { return request != null ? request.uri() : URI.create("http://localhost"); }
    @Override public HttpClient.Version version() { return HttpClient.Version.HTTP_2; }
    @Override public Optional<SSLSession> sslSession() { return Optional.empty(); }
}
