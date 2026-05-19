package com.chorus.engine.swarm;

import com.chorus.engine.core.context.Message;
import com.chorus.engine.core.context.TokenCount;
import com.chorus.engine.core.reactive.CancellationToken;
import com.chorus.engine.llm.ChatRequest;
import com.chorus.engine.llm.ChatResponse;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.*;

class CostRouterTest {

    @Test
    void routes_simple_requests_to_cheap_model() {
        FakeLlmClient cheap = new FakeLlmClient("cheap");
        FakeLlmClient capable = new FakeLlmClient("capable");

        Function<ChatRequest, String> classifier = req -> "simple";
        Map<String, String> modelMap = Map.of("simple", "gpt-4o-mini", "complex", "gpt-4o");
        Set<String> cheapModels = Set.of("gpt-4o-mini");

        CostRouter router = new CostRouter(capable, cheap, classifier, modelMap, cheapModels);

        ChatResponse cheapResponse = new ChatResponse(
            "id", "gpt-4o-mini", "cheap", Message.assistant("hello"),
            new TokenCount(1, 1, "test"), Duration.ZERO, "stop", null, null, Map.of()
        );
        cheap.enqueue(cheapResponse);

        ChatRequest request = ChatRequest.builder().model("gpt-4o").messages(List.of(Message.user("hi"))).build();
        ChatResponse result = router.complete(request, CancellationToken.create());

        assertThat(result.model()).isEqualTo("gpt-4o-mini");
        assertThat(result.message().content()).isEqualTo("hello");
    }

    @Test
    void routes_complex_requests_to_capable_model() {
        FakeLlmClient cheap = new FakeLlmClient("cheap");
        FakeLlmClient capable = new FakeLlmClient("capable");

        Function<ChatRequest, String> classifier = req -> "complex";
        Map<String, String> modelMap = Map.of("simple", "gpt-4o-mini", "complex", "gpt-4o");
        Set<String> cheapModels = Set.of("gpt-4o-mini");

        CostRouter router = new CostRouter(capable, cheap, classifier, modelMap, cheapModels);

        ChatResponse capableResponse = new ChatResponse(
            "id", "gpt-4o", "capable", Message.assistant("complex answer"),
            new TokenCount(1, 1, "test"), Duration.ZERO, "stop", null, null, Map.of()
        );
        capable.enqueue(capableResponse);

        ChatRequest request = ChatRequest.builder().model("gpt-4o").messages(List.of(Message.user("hard"))).build();
        ChatResponse result = router.complete(request, CancellationToken.create());

        assertThat(result.model()).isEqualTo("gpt-4o");
        assertThat(result.message().content()).isEqualTo("complex answer");
    }

    @Test
    void falls_back_to_capable_on_cheap_failure() {
        FakeLlmClient cheap = new FakeLlmClient("cheap");
        FakeLlmClient capable = new FakeLlmClient("capable");

        Function<ChatRequest, String> classifier = req -> "simple";
        Map<String, String> modelMap = Map.of("simple", "gpt-4o-mini");
        Set<String> cheapModels = Set.of("gpt-4o-mini");

        CostRouter router = new CostRouter(capable, cheap, classifier, modelMap, cheapModels);

        // cheap has no enqueued response → will throw
        ChatResponse fallbackResponse = new ChatResponse(
            "id", "gpt-4o", "capable", Message.assistant("fallback"),
            new TokenCount(1, 1, "test"), Duration.ZERO, "stop", null, null, Map.of()
        );
        capable.enqueue(fallbackResponse);

        ChatRequest request = ChatRequest.builder().model("gpt-4o").messages(List.of(Message.user("hi"))).build();
        ChatResponse result = router.complete(request, CancellationToken.create());

        assertThat(result.message().content()).isEqualTo("fallback");
    }

    @Test
    void health_is_healthy_when_either_client_is_healthy() {
        FakeLlmClient cheap = new FakeLlmClient("cheap");
        FakeLlmClient capable = new FakeLlmClient("capable");

        CostRouter router = new CostRouter(capable, cheap, req -> "simple", Map.of(), Set.of());
        assertThat(router.health()).isEqualTo(com.chorus.engine.llm.LlmClient.HealthStatus.HEALTHY);
    }

    @Test
    void provider_name_is_composite() {
        FakeLlmClient cheap = new FakeLlmClient("cheap");
        FakeLlmClient capable = new FakeLlmClient("capable");
        CostRouter router = new CostRouter(capable, cheap, req -> "simple", Map.of(), Set.of());
        assertThat(router.providerName()).isEqualTo("cost-router[capable/cheap]");
    }
}
