package com.chorus.engine.swarm;

import com.chorus.engine.core.reactive.CancellationToken;
import com.chorus.engine.llm.ChatRequest;
import com.chorus.engine.llm.ChatResponse;
import com.chorus.engine.llm.LlmClient;
import com.chorus.engine.llm.StreamEvent;
import org.jspecify.annotations.NonNull;

import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.Flow;
import java.util.function.Function;

/**
 * Routes LLM requests to cheaper models for simple tasks.
 *
 * <p>A configurable classifier determines task complexity. The router swaps
 * the model name in the request and delegates to the appropriate client.
 * If the cheap client fails, the router falls back to the capable client.
 */
public final class CostRouter implements LlmClient {

    private final LlmClient capableClient;
    private final LlmClient cheapClient;
    private final Function<ChatRequest, String> classifier;
    private final Map<String, String> modelMap;
    private final Set<String> cheapModels;

    public CostRouter(
        @NonNull LlmClient capableClient,
        @NonNull LlmClient cheapClient,
        @NonNull Function<ChatRequest, String> classifier,
        @NonNull Map<String, String> modelMap,
        @NonNull Set<String> cheapModels
    ) {
        this.capableClient = Objects.requireNonNull(capableClient, "capableClient cannot be null");
        this.cheapClient = Objects.requireNonNull(cheapClient, "cheapClient cannot be null");
        this.classifier = Objects.requireNonNull(classifier, "classifier cannot be null");
        this.modelMap = Map.copyOf(Objects.requireNonNull(modelMap, "modelMap cannot be null"));
        this.cheapModels = Set.copyOf(Objects.requireNonNull(cheapModels, "cheapModels cannot be null"));
    }

    @Override
    public Flow.@NonNull Publisher<StreamEvent> stream(
        @NonNull ChatRequest request,
        @NonNull CancellationToken cancellationToken
    ) {
        RoutedRequest routed = route(request);
        LlmClient client = routed.isCheap() ? cheapClient : capableClient;
        try {
            return client.stream(routed.request(), cancellationToken);
        } catch (Exception e) {
            return capableClient.stream(request, cancellationToken);
        }
    }

    @Override
    public @NonNull ChatResponse complete(
        @NonNull ChatRequest request,
        @NonNull CancellationToken cancellationToken
    ) {
        RoutedRequest routed = route(request);
        LlmClient client = routed.isCheap() ? cheapClient : capableClient;
        try {
            return client.complete(routed.request(), cancellationToken);
        } catch (Exception e) {
            return capableClient.complete(request, cancellationToken);
        }
    }

    @Override
    public @NonNull HealthStatus health() {
        HealthStatus capable = capableClient.health();
        HealthStatus cheap = cheapClient.health();
        if (capable == HealthStatus.HEALTHY || cheap == HealthStatus.HEALTHY) {
            return HealthStatus.HEALTHY;
        }
        if (capable == HealthStatus.DEGRADED || cheap == HealthStatus.DEGRADED) {
            return HealthStatus.DEGRADED;
        }
        return HealthStatus.UNAVAILABLE;
    }

    @Override
    public @NonNull String providerName() {
        return "cost-router[" + capableClient.providerName() + "/" + cheapClient.providerName() + "]";
    }

    private @NonNull RoutedRequest route(@NonNull ChatRequest request) {
        String classification = classifier.apply(request);
        String mappedModel = modelMap.getOrDefault(classification, request.model());
        ChatRequest routed = ChatRequest.builder()
            .model(mappedModel)
            .messages(request.messages())
            .tools(request.tools())
            .temperature(request.temperature())
            .maxTokens(request.maxTokens())
            .responseFormat(request.responseFormat())
            .stopSequence(request.stopSequence())
            .providerExtras(request.providerExtras())
            .build();
        return new RoutedRequest(routed, cheapModels.contains(mappedModel));
    }

    private record RoutedRequest(@NonNull ChatRequest request, boolean isCheap) {}
}
