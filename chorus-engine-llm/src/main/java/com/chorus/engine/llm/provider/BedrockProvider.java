package com.chorus.engine.llm.provider;

import com.chorus.engine.core.context.Message;
import com.chorus.engine.core.context.TokenCount;
import com.chorus.engine.core.reactive.CancellationToken;
import com.chorus.engine.llm.*;
import com.chorus.engine.llm.retry.CircuitBreaker;
import com.chorus.engine.llm.retry.RetryPolicy;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.Flow;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * AWS Bedrock LLM provider using the Converse API (converse-stream).
 *
 * <p>Supports models available on AWS Bedrock via the unified Converse API:
 * Anthropic Claude (e.g. {@code anthropic.claude-3-5-sonnet-20241022-v2:0}),
 * Amazon Titan, Meta Llama 3, Cohere Command, Mistral, AI21, etc.
 *
 * <p>Authentication uses AWS SigV4, computed inline (no AWS SDK dependency).
 * Credentials: provide access key + secret key, or set standard AWS environment variables
 * ({@code AWS_ACCESS_KEY_ID}, {@code AWS_SECRET_ACCESS_KEY}, {@code AWS_SESSION_TOKEN}).
 *
 * <p>Usage:
 * <pre>{@code
 * registry.registerBedrock(
 *     "bedrock-claude",
 *     "us-east-1",
 *     System.getenv("AWS_ACCESS_KEY_ID"),
 *     System.getenv("AWS_SECRET_ACCESS_KEY"),
 *     null  // session token, null if not using temporary credentials
 * );
 * ChatRequest req = ChatRequest.builder()
 *     .model("anthropic.claude-3-5-sonnet-20241022-v2:0")
 *     ...build();
 * }</pre>
 */
public final class BedrockProvider implements LlmClient {

    private static final String SERVICE = "bedrock";
    private static final DateTimeFormatter DATE_TIME_FMT = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'").withZone(ZoneOffset.UTC);
    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ofPattern("yyyyMMdd").withZone(ZoneOffset.UTC);

    private final String providerName;
    private final String region;
    private final String accessKeyId;
    private final String secretAccessKey;
    @Nullable private final String sessionToken;
    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;
    private final RetryPolicy retryPolicy;
    private final CircuitBreaker circuitBreaker;
    private final ExecutorService executor;
    private final AtomicBoolean closed = new AtomicBoolean(false);

    public BedrockProvider(
        @NonNull String providerName,
        @NonNull String region,
        @NonNull String accessKeyId,
        @NonNull String secretAccessKey,
        @Nullable String sessionToken,
        @NonNull HttpClient httpClient,
        @NonNull ObjectMapper objectMapper,
        @NonNull RetryPolicy retryPolicy,
        @NonNull CircuitBreaker circuitBreaker,
        @NonNull ExecutorService executor
    ) {
        this.providerName = providerName;
        this.region = region;
        this.accessKeyId = accessKeyId;
        this.secretAccessKey = secretAccessKey;
        this.sessionToken = sessionToken;
        this.httpClient = httpClient;
        this.objectMapper = objectMapper;
        this.retryPolicy = retryPolicy;
        this.circuitBreaker = circuitBreaker;
        this.executor = executor;
    }

    @Override
    public Flow.@NonNull Publisher<StreamEvent> stream(@NonNull ChatRequest request, @NonNull CancellationToken cancellationToken) {
        if (closed.get()) throw new IllegalStateException("Provider is closed");
        if (!circuitBreaker.allowsRequest()) {
            return subscriber -> {
                subscriber.onSubscribe(new Flow.Subscription() {
                    @Override public void request(long n) {}
                    @Override public void cancel() {}
                });
                subscriber.onError(new RuntimeException("Circuit breaker is OPEN for provider " + providerName));
            };
        }
        return new BedrockStreamPublisher(request, cancellationToken);
    }

    @Override
    public @NonNull ChatResponse complete(@NonNull ChatRequest request, @NonNull CancellationToken cancellationToken) {
        CompletableFuture<ChatResponse> future = new CompletableFuture<>();
        StringBuilder content = new StringBuilder();
        AtomicReference<String> finishReason = new AtomicReference<>();
        AtomicInteger inputTokens = new AtomicInteger(0);
        AtomicInteger outputTokens = new AtomicInteger(0);

        stream(request, cancellationToken).subscribe(new Flow.Subscriber<>() {
            @Override public void onSubscribe(Flow.Subscription s) { s.request(Long.MAX_VALUE); }
            @Override public void onNext(StreamEvent event) {
                switch (event) {
                    case StreamEvent.Token t -> content.append(t.token());
                    case StreamEvent.Finish f -> {
                        finishReason.set(f.finishReason());
                        inputTokens.set(f.promptTokens());
                        outputTokens.set(f.completionTokens());
                    }
                    case StreamEvent.Error e -> future.completeExceptionally(new RuntimeException(e.errorMessage()));
                    default -> {}
                }
            }
            @Override public void onError(Throwable t) { future.completeExceptionally(t); }
            @Override public void onComplete() {
                future.complete(new ChatResponse(
                    UUID.randomUUID().toString(), request.model(), providerName,
                    Message.assistant(content.toString()),
                    new TokenCount(inputTokens.get(), outputTokens.get(), "bedrock"),
                    Duration.ZERO, finishReason.get(), null, null, Map.of()
                ));
            }
        });

        try {
            return future.get(120L, TimeUnit.SECONDS);
        } catch (TimeoutException e) {
            throw new RuntimeException("Bedrock request timed out", e);
        } catch (Exception e) {
            throw new RuntimeException("Bedrock request failed", e);
        }
    }

    @Override
    public @NonNull HealthStatus health() {
        if (circuitBreaker.isOpen()) return HealthStatus.UNAVAILABLE;
        return HealthStatus.HEALTHY;
    }

    @Override
    public @NonNull String providerName() { return providerName; }

    public void close() {
        if (closed.compareAndSet(false, true)) {
            executor.shutdown();
            try {
                if (!executor.awaitTermination(5, TimeUnit.SECONDS)) executor.shutdownNow();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                executor.shutdownNow();
            }
        }
    }

    // ---- Inner: Stream Publisher ----

    private final class BedrockStreamPublisher implements Flow.Publisher<StreamEvent> {
        private final ChatRequest request;
        private final CancellationToken cancellationToken;

        BedrockStreamPublisher(ChatRequest request, CancellationToken cancellationToken) {
            this.request = request;
            this.cancellationToken = cancellationToken;
        }

        @Override
        public void subscribe(Flow.Subscriber<? super StreamEvent> subscriber) {
            AtomicBoolean cancelled = new AtomicBoolean(false);
            AtomicLong demand = new AtomicLong(0);
            subscriber.onSubscribe(new Flow.Subscription() {
                @Override public void request(long n) {
                    demand.getAndUpdate(d -> { long s = d + n; return s < 0 ? Long.MAX_VALUE : s; });
                }
                @Override public void cancel() { cancelled.set(true); }
            });
            try {
                executor.submit(() -> {
                    try {
                        executeWithRetry(subscriber, cancelled, demand);
                        if (!cancelled.get()) subscriber.onComplete();
                    } catch (Exception e) {
                        if (!cancelled.get()) subscriber.onError(e);
                    }
                });
            } catch (Exception e) {
                if (!cancelled.get()) subscriber.onError(e);
            }
        }

        private void executeWithRetry(Flow.Subscriber<? super StreamEvent> subscriber,
                                      AtomicBoolean cancelled, AtomicLong demand) throws Exception {
            int attempt = 0;
            Exception lastError = null;
            while (attempt < retryPolicy.maxAttempts()) {
                if (cancelled.get() || cancellationToken.isCancelled()) throw new CancellationException("cancelled");
                try {
                    String payload = buildPayload(request);
                    HttpRequest httpRequest = buildSignedRequest(request.model(), payload);
                    HttpResponse<InputStream> response = httpClient.send(httpRequest, HttpResponse.BodyHandlers.ofInputStream());
                    if (response.statusCode() == 200) {
                        circuitBreaker.recordSuccess();
                        try (InputStream stream = response.body()) {
                            parseEventStream(stream, subscriber, cancelled, demand);
                        }
                        return;
                    }
                    String body = new String(response.body().readAllBytes(), StandardCharsets.UTF_8);
                    lastError = new RuntimeException("HTTP " + response.statusCode() + ": " + body);
                    if (!retryPolicy.isRetryable(response.statusCode())) throw lastError;
                    circuitBreaker.recordFailure();
                } catch (IOException | InterruptedException e) {
                    lastError = e;
                    circuitBreaker.recordFailure();
                    if (e instanceof InterruptedException) Thread.currentThread().interrupt();
                }
                attempt++;
                if (attempt < retryPolicy.maxAttempts()) Thread.sleep(retryPolicy.computeDelay(attempt).toMillis());
            }
            throw lastError != null ? lastError : new RuntimeException("Max retries exceeded");
        }

        /**
         * Bedrock Converse streaming returns an AWS EventStream-encoded response.
         * Each event is framed with a prelude (lengths), headers, and payload.
         * For simplicity this implementation reads the JSON payload chunks from
         * the response body using newline-delimited JSON within the event payload,
         * compatible with the Converse API event format.
         */
        private void parseEventStream(InputStream stream, Flow.Subscriber<? super StreamEvent> subscriber,
                                      AtomicBoolean cancelled, AtomicLong demand) {
            // AWS EventStream binary framing: read frames and extract JSON payloads
            try {
                while (!cancelled.get()) {
                    // Read total byte length (4 bytes big-endian)
                    byte[] lenBytes = stream.readNBytes(4);
                    if (lenBytes.length < 4) break;
                    int totalLen = ((lenBytes[0] & 0xFF) << 24) | ((lenBytes[1] & 0xFF) << 16)
                        | ((lenBytes[2] & 0xFF) << 8) | (lenBytes[3] & 0xFF);
                    if (totalLen <= 0) break;

                    // Read full frame (minus the 4 bytes we already read)
                    byte[] frame = stream.readNBytes(totalLen - 4);
                    if (frame.length < totalLen - 4) break;

                    // Frame structure: [4 headers_len][4 crc32][N headers][payload][4 crc32]
                    // headers_len is at offset 0 in frame (after our 4-byte total len)
                    int headersLen = ((frame[0] & 0xFF) << 24) | ((frame[1] & 0xFF) << 16)
                        | ((frame[2] & 0xFF) << 8) | (frame[3] & 0xFF);
                    // Skip: 4 (headers_len) + 4 (prelude_crc) + headersLen
                    int payloadStart = 4 + 4 + headersLen;
                    // Payload ends 4 bytes before the frame end (message CRC)
                    int payloadEnd = frame.length - 4;
                    if (payloadStart >= payloadEnd) continue;

                    byte[] payload = Arrays.copyOfRange(frame, payloadStart, payloadEnd);
                    String json = new String(payload, StandardCharsets.UTF_8).trim();
                    if (json.isEmpty()) continue;

                    try {
                        JsonNode event = objectMapper.readTree(json);
                        // Bedrock Converse events have nested structure
                        if (event.has("contentBlockDelta")) {
                            String token = event.path("contentBlockDelta").path("delta").path("text").asText("");
                            if (!token.isEmpty()) emit(subscriber, new StreamEvent.Token(token, 0, null), demand, cancelled);
                        } else if (event.has("messageStop")) {
                            String reason = event.path("messageStop").path("stopReason").asText("end_turn");
                            emit(subscriber, new StreamEvent.Finish(reason, 0, 0), demand, cancelled);
                        } else if (event.has("metadata")) {
                            JsonNode usage = event.path("metadata").path("usage");
                            int inputTok = usage.path("inputTokens").asInt(0);
                            int outputTok = usage.path("outputTokens").asInt(0);
                            // Emit a usage-only Finish if not already sent
                            if (inputTok > 0 || outputTok > 0) {
                                emit(subscriber, new StreamEvent.Finish("end_turn", inputTok, outputTok), demand, cancelled);
                            }
                        }
                    } catch (JsonProcessingException e) {
                        // Non-JSON payload (e.g. initial-response frame) — ignore
                    }
                }
            } catch (IOException e) {
                if (!cancelled.get()) subscriber.onError(e);
            }
        }

        private void emit(Flow.Subscriber<? super StreamEvent> subscriber, StreamEvent event,
                          AtomicLong demand, AtomicBoolean cancelled) {
            while (demand.get() <= 0 && !cancelled.get()) {
                try { Thread.sleep(1); } catch (InterruptedException e) { Thread.currentThread().interrupt(); return; }
            }
            if (!cancelled.get()) { demand.decrementAndGet(); subscriber.onNext(event); }
        }

        private @NonNull String buildPayload(@NonNull ChatRequest request) throws JsonProcessingException {
            ObjectNode body = objectMapper.createObjectNode();

            // System prompt
            List<Message> messages = request.messages();
            List<Message> systemMsgs = messages.stream().filter(m -> m.role().name().equals("SYSTEM")).toList();
            List<Message> conversationMsgs = messages.stream().filter(m -> !m.role().name().equals("SYSTEM")).toList();

            if (!systemMsgs.isEmpty()) {
                ArrayNode system = body.putArray("system");
                for (Message sys : systemMsgs) {
                    system.addObject().put("text", sys.content());
                }
            }

            ArrayNode msgs = body.putArray("messages");
            for (Message msg : conversationMsgs) {
                ObjectNode m = msgs.addObject();
                m.put("role", msg.role().name().equalsIgnoreCase("assistant") ? "assistant" : "user");
                ArrayNode content = m.putArray("content");
                content.addObject().put("text", msg.content());
            }

            ObjectNode inferenceConfig = body.putObject("inferenceConfig");
            if (request.maxTokens() > 0) inferenceConfig.put("maxTokens", request.maxTokens());
            inferenceConfig.put("temperature", (float) request.temperature());

            return objectMapper.writeValueAsString(body);
        }

        private @NonNull HttpRequest buildSignedRequest(@NonNull String modelId, @NonNull String payload) {
            String endpoint = "https://bedrock-runtime." + region + ".amazonaws.com";
            String path = "/model/" + modelId + "/converse-stream";
            String url = endpoint + path;

            ZonedDateTime now = ZonedDateTime.now(ZoneOffset.UTC);
            String dateTime = DATE_TIME_FMT.format(now);
            String date = DATE_FMT.format(now);

            String payloadHash = sha256Hex(payload.getBytes(StandardCharsets.UTF_8));
            String authHeader = buildSigV4Auth(
                "POST", path, "", "bedrock-runtime", endpoint, dateTime, date, payloadHash, payload
            );

            HttpRequest.Builder builder = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("Content-Type", "application/json")
                .header("X-Amz-Date", dateTime)
                .header("X-Amz-Content-Sha256", payloadHash)
                .header("Authorization", authHeader)
                .timeout(Duration.ofSeconds(120))
                .POST(HttpRequest.BodyPublishers.ofString(payload));

            if (sessionToken != null && !sessionToken.isBlank()) {
                builder.header("X-Amz-Security-Token", sessionToken);
            }

            return builder.build();
        }
    }

    // ---- AWS SigV4 implementation (no external deps) ----

    private @NonNull String buildSigV4Auth(@NonNull String method, @NonNull String path,
                                            @NonNull String queryString, @NonNull String service,
                                            @NonNull String host, @NonNull String dateTime,
                                            @NonNull String date, @NonNull String payloadHash,
                                            @NonNull String payload) {
        // Step 1: Canonical request
        String canonicalHeaders = "content-type:application/json\nhost:" + host.replace("https://", "") + "\nx-amz-content-sha256:" + payloadHash + "\nx-amz-date:" + dateTime + "\n";
        String signedHeaders = "content-type;host;x-amz-content-sha256;x-amz-date";
        if (sessionToken != null && !sessionToken.isBlank()) {
            canonicalHeaders += "x-amz-security-token:" + sessionToken + "\n";
            signedHeaders += ";x-amz-security-token";
        }
        String canonicalRequest = method + "\n" + path + "\n" + queryString + "\n"
            + canonicalHeaders + "\n" + signedHeaders + "\n" + payloadHash;

        // Step 2: String to sign
        String credentialScope = date + "/" + region + "/" + service + "/aws4_request";
        String stringToSign = "AWS4-HMAC-SHA256\n" + dateTime + "\n" + credentialScope + "\n"
            + sha256Hex(canonicalRequest.getBytes(StandardCharsets.UTF_8));

        // Step 3: Signing key
        byte[] kDate = hmacSha256(("AWS4" + secretAccessKey).getBytes(StandardCharsets.UTF_8), date);
        byte[] kRegion = hmacSha256(kDate, region);
        byte[] kService = hmacSha256(kRegion, service);
        byte[] kSigning = hmacSha256(kService, "aws4_request");
        String signature = hex(hmacSha256(kSigning, stringToSign));

        return "AWS4-HMAC-SHA256 Credential=" + accessKeyId + "/" + credentialScope
            + ", SignedHeaders=" + signedHeaders + ", Signature=" + signature;
    }

    private static @NonNull String sha256Hex(byte[] data) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return hex(digest.digest(data));
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException("SHA-256 unavailable", e);
        }
    }

    private static byte[] hmacSha256(byte[] key, @NonNull String data) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key, "HmacSHA256"));
            return mac.doFinal(data.getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            throw new RuntimeException("HmacSHA256 failed", e);
        }
    }

    private static @NonNull String hex(byte[] bytes) {
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) sb.append(String.format("%02x", b));
        return sb.toString();
    }
}
