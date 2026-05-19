package com.chorus.engine.a2a;

import com.chorus.engine.a2a.card.AgentCard;
import com.chorus.engine.a2a.card.Authentication;
import com.chorus.engine.a2a.card.Capabilities;
import com.chorus.engine.a2a.card.Skill;
import com.chorus.engine.a2a.client.A2aClient;
import com.chorus.engine.a2a.task.Message;
import com.chorus.engine.a2a.task.Part;
import com.chorus.engine.a2a.task.Task;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.*;

class A2aClientTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void fetchAgentCard_success() throws Exception {
        AgentCard card = new AgentCard("test", "desc", "http://localhost", "1.0",
            new Capabilities(true, false), new Authentication(List.of("Bearer")),
            List.of(new Skill("s1", "Skill", "desc", List.of("tag"))));
        String json = mapper.writeValueAsString(card);

        MockHttpClient mock = new MockHttpClient().withResponse(200, json);
        A2aClient client = new A2aClient(mock, "http://localhost", null);

        AgentCard result = client.fetchAgentCard();

        assertThat(result.name()).isEqualTo("test");
        assertThat(mock.requests).hasSize(1);
        assertThat(mock.requests.get(0).uri().getPath()).isEqualTo("/.well-known/agent.json");
        assertThat(mock.requests.get(0).method()).isEqualTo("GET");
    }

    @Test
    void sendTask_success() throws Exception {
        Task task = new Task("t1", "s1", Task.Status.SUBMITTED,
            List.of(new Message(Message.Role.USER, List.of(new Part.TextPart("hello")), null)),
            null, null);
        String json = mapper.writeValueAsString(task);

        MockHttpClient mock = new MockHttpClient().withResponse(200, json);
        A2aClient client = new A2aClient(mock, "http://localhost", null);

        Task result = client.sendTask(task);

        assertThat(result.id()).isEqualTo("t1");
        assertThat(mock.requests).hasSize(1);
        assertThat(mock.requests.get(0).uri().getPath()).isEqualTo("/tasks/send");
        assertThat(mock.requests.get(0).method()).isEqualTo("POST");
    }

    @Test
    void getTask_success() throws Exception {
        Task task = new Task("t1", "s1", Task.Status.COMPLETED,
            List.of(), null, null);
        String json = mapper.writeValueAsString(task);

        MockHttpClient mock = new MockHttpClient().withResponse(200, json);
        A2aClient client = new A2aClient(mock, "http://localhost", null);

        Task result = client.getTask("t1");

        assertThat(result.status()).isEqualTo(Task.Status.COMPLETED);
        assertThat(mock.requests).hasSize(1);
        assertThat(mock.requests.get(0).uri().getPath()).isEqualTo("/tasks/t1");
        assertThat(mock.requests.get(0).method()).isEqualTo("GET");
    }

    @Test
    void cancelTask_success() throws Exception {
        Task task = new Task("t1", "s1", Task.Status.CANCELED,
            List.of(), null, null);
        String json = mapper.writeValueAsString(task);

        MockHttpClient mock = new MockHttpClient().withResponse(200, json);
        A2aClient client = new A2aClient(mock, "http://localhost", null);

        Task result = client.cancelTask("t1");

        assertThat(result.status()).isEqualTo(Task.Status.CANCELED);
        assertThat(mock.requests).hasSize(1);
        assertThat(mock.requests.get(0).uri().getPath()).isEqualTo("/tasks/t1/cancel");
        assertThat(mock.requests.get(0).method()).isEqualTo("POST");
    }

    @Test
    void subscribeToTask_emitsTasksFromSse() throws Exception {
        Task task1 = new Task("t1", "s1", Task.Status.WORKING,
            List.of(), null, null);
        Task task2 = new Task("t1", "s1", Task.Status.COMPLETED,
            List.of(), null, null);

        String sse = "data: " + mapper.writeValueAsString(task1) + "\n\n" +
                     "data: " + mapper.writeValueAsString(task2) + "\n\n";

        MockHttpClient mock = new MockHttpClient().withStreamResponse(200,
            new ByteArrayInputStream(sse.getBytes(StandardCharsets.UTF_8)));
        A2aClient client = new A2aClient(mock, "http://localhost", null);

        List<Task> collected = new ArrayList<>();
        CountDownLatch latch = new CountDownLatch(1);

        client.subscribeToTask("t1").subscribe(new Flow.Subscriber<Task>() {
            @Override public void onSubscribe(Flow.Subscription s) { s.request(Long.MAX_VALUE); }
            @Override public void onNext(Task task) { collected.add(task); }
            @Override public void onError(Throwable t) { latch.countDown(); }
            @Override public void onComplete() { latch.countDown(); }
        });

        assertThat(latch.await(5, TimeUnit.SECONDS)).isTrue();
        assertThat(collected).hasSize(2);
        assertThat(collected.get(0).status()).isEqualTo(Task.Status.WORKING);
        assertThat(collected.get(1).status()).isEqualTo(Task.Status.COMPLETED);
    }

    @Test
    void subscribeToTask_handlesDoneMarker() throws Exception {
        Task task1 = new Task("t1", "s1", Task.Status.COMPLETED,
            List.of(), null, null);

        String sse = "data: " + mapper.writeValueAsString(task1) + "\n\n" +
                     "data: [DONE]\n\n";

        MockHttpClient mock = new MockHttpClient().withStreamResponse(200,
            new ByteArrayInputStream(sse.getBytes(StandardCharsets.UTF_8)));
        A2aClient client = new A2aClient(mock, "http://localhost", null);

        List<Task> collected = new ArrayList<>();
        CountDownLatch latch = new CountDownLatch(1);

        client.subscribeToTask("t1").subscribe(new Flow.Subscriber<Task>() {
            @Override public void onSubscribe(Flow.Subscription s) { s.request(Long.MAX_VALUE); }
            @Override public void onNext(Task task) { collected.add(task); }
            @Override public void onError(Throwable t) { latch.countDown(); }
            @Override public void onComplete() { latch.countDown(); }
        });

        assertThat(latch.await(5, TimeUnit.SECONDS)).isTrue();
        assertThat(collected).hasSize(1);
    }

    @Test
    void authToken_isIncludedInRequests() throws Exception {
        AgentCard card = new AgentCard("test", "desc", "http://localhost", "1.0",
            new Capabilities(true, false), new Authentication(List.of("Bearer")),
            List.of(new Skill("s1", "Skill", "desc", List.of("tag"))));
        MockHttpClient mock = new MockHttpClient().withResponse(200, mapper.writeValueAsString(card));
        A2aClient client = new A2aClient(mock, "http://localhost", "secret123");

        client.fetchAgentCard();

        assertThat(mock.requests).hasSize(1);
        assertThat(mock.requests.get(0).headers().firstValue("Authorization"))
            .hasValue("Bearer secret123");
    }

    @Test
    void fetchAgentCard_failureThrows() {
        MockHttpClient mock = new MockHttpClient().withResponse(404, "Not found");
        A2aClient client = new A2aClient(mock, "http://localhost", null);

        assertThatThrownBy(client::fetchAgentCard)
            .isInstanceOf(java.io.IOException.class)
            .hasMessageContaining("HTTP 404");
    }
}
