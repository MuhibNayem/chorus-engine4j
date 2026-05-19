package com.chorus.engine.llm.testutil;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Map;
import java.util.Optional;
import javax.net.ssl.SSLSession;

public class FakeHttpResponse<T> implements HttpResponse<T> {

    private final int statusCode;
    private final HttpRequest request;
    private final T body;

    public FakeHttpResponse(int statusCode, T body) {
        this(statusCode, null, body);
    }

    public FakeHttpResponse(int statusCode, HttpRequest request, T body) {
        this.statusCode = statusCode;
        this.request = request;
        this.body = body;
    }

    @Override
    public int statusCode() {
        return statusCode;
    }

    @Override
    public HttpRequest request() {
        return request != null ? request : HttpRequest.newBuilder(URI.create("http://localhost")).GET().build();
    }

    @Override
    public Optional<HttpResponse<T>> previousResponse() {
        return Optional.empty();
    }

    @Override
    public HttpHeaders headers() {
        return HttpHeaders.of(Map.of(), (k, v) -> true);
    }

    @Override
    public T body() {
        return body;
    }

    @Override
    public Optional<SSLSession> sslSession() {
        return Optional.empty();
    }

    @Override
    public URI uri() {
        return request != null ? request.uri() : URI.create("http://localhost");
    }

    @Override
    public HttpClient.Version version() {
        return HttpClient.Version.HTTP_1_1;
    }
}
