package com.chorus.engine.a2a.server;

import com.chorus.engine.a2a.card.AgentCard;
import com.chorus.engine.a2a.card.Authentication;
import com.chorus.engine.a2a.card.Capabilities;
import com.chorus.engine.a2a.card.Skill;
import com.chorus.engine.a2a.task.Message;
import com.chorus.engine.a2a.task.Part;
import com.chorus.engine.a2a.task.Task;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.net.http.HttpRequest;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.*;

class A2aHttpHandlerTest {

    private final ObjectMapper mapper = new ObjectMapper();

    private A2aServer createFakeServer() {
        return new A2aServer() {
            @Override
            public AgentCard getAgentCard() {
                return new AgentCard("fake-agent", "A fake agent", "http://localhost", "1.0",
                    new Capabilities(true, false),
                    new Authentication(List.of("Bearer")),
                    List.of(new Skill("s1", "Skill 1", "Desc", List.of("tag"))));
            }

            @Override
            public Task onSendTask(Task task) {
                return new Task(task.id(), task.sessionId(), Task.Status.SUBMITTED,
                    task.history(), task.artifacts(), task.metadata());
            }

            @Override
            public Task onGetTask(String taskId) {
                return new Task(taskId, "session-1", Task.Status.COMPLETED,
                    List.of(), null, null);
            }

            @Override
            public Task onCancelTask(String taskId) {
                return new Task(taskId, "session-1", Task.Status.CANCELED,
                    List.of(), null, null);
            }

            @Override
            public Flow.Publisher<TaskUpdate> onSubscribeTask(String taskId) {
                return subscriber -> {
                    subscriber.onSubscribe(new Flow.Subscription() {
                        @Override public void request(long n) {}
                        @Override public void cancel() {}
                    });
                    subscriber.onNext(new TaskUpdate(taskId, Task.Status.WORKING, null, null));
                    subscriber.onComplete();
                };
            }
        };
    }

    private A2aServer createThrowingServer() {
        return new A2aServer() {
            @Override
            public AgentCard getAgentCard() {
                throw new RuntimeException("card error");
            }

            @Override
            public Task onSendTask(Task task) {
                throw new RuntimeException("send error");
            }

            @Override
            public Task onGetTask(String taskId) {
                throw new RuntimeException("get error");
            }

            @Override
            public Task onCancelTask(String taskId) {
                throw new RuntimeException("cancel error");
            }

            @Override
            public Flow.Publisher<TaskUpdate> onSubscribeTask(String taskId) {
                throw new RuntimeException("subscribe error");
            }
        };
    }

    @Test
    void handle_agentCard_returns200() throws Exception {
        A2aHttpHandler handler = new A2aHttpHandler(createFakeServer());
        HttpRequest request = HttpRequest.newBuilder()
            .uri(URI.create("http://localhost/.well-known/agent.json"))
            .GET()
            .build();

        A2aHttpResponse response = handler.handle(request);

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).isNotNull();
        AgentCard card = mapper.readValue(response.body(), AgentCard.class);
        assertThat(card.name()).isEqualTo("fake-agent");
        assertThat(response.headers().get("Content-Type")).containsExactly("application/json");
    }

    @Test
    void handle_sendTask_returns200() throws Exception {
        A2aHttpHandler handler = new A2aHttpHandler(createFakeServer());
        Task task = new Task("t1", "s1", Task.Status.SUBMITTED,
            List.of(new Message(Message.Role.USER, List.of(new Part.TextPart("hello")), null)),
            null, null);
        String body = mapper.writeValueAsString(task);

        HttpRequest request = HttpRequest.newBuilder()
            .uri(URI.create("http://localhost/tasks/send"))
            .POST(HttpRequest.BodyPublishers.ofString(body))
            .build();

        A2aHttpResponse response = handler.handle(request);

        assertThat(response.statusCode()).isEqualTo(200);
        Task result = mapper.readValue(response.body(), Task.class);
        assertThat(result.id()).isEqualTo("t1");
        assertThat(result.status()).isEqualTo(Task.Status.SUBMITTED);
    }

    @Test
    void handle_getTask_returns200() throws Exception {
        A2aHttpHandler handler = new A2aHttpHandler(createFakeServer());
        HttpRequest request = HttpRequest.newBuilder()
            .uri(URI.create("http://localhost/tasks/t1"))
            .GET()
            .build();

        A2aHttpResponse response = handler.handle(request);

        assertThat(response.statusCode()).isEqualTo(200);
        Task result = mapper.readValue(response.body(), Task.class);
        assertThat(result.id()).isEqualTo("t1");
        assertThat(result.status()).isEqualTo(Task.Status.COMPLETED);
    }

    @Test
    void handle_cancelTask_returns200() throws Exception {
        A2aHttpHandler handler = new A2aHttpHandler(createFakeServer());
        HttpRequest request = HttpRequest.newBuilder()
            .uri(URI.create("http://localhost/tasks/t1/cancel"))
            .POST(HttpRequest.BodyPublishers.noBody())
            .build();

        A2aHttpResponse response = handler.handle(request);

        assertThat(response.statusCode()).isEqualTo(200);
        Task result = mapper.readValue(response.body(), Task.class);
        assertThat(result.status()).isEqualTo(Task.Status.CANCELED);
    }

    @Test
    void handle_subscribeTask_returnsSseStream() throws Exception {
        A2aHttpHandler handler = new A2aHttpHandler(createFakeServer());
        HttpRequest request = HttpRequest.newBuilder()
            .uri(URI.create("http://localhost/tasks/t1/subscribe"))
            .GET()
            .build();

        A2aHttpResponse response = handler.handle(request);

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.stream()).isNotNull();
        assertThat(response.headers().get("Content-Type")).containsExactly("text/event-stream");

        List<String> events = new ArrayList<>();
        CountDownLatch latch = new CountDownLatch(1);
        Flow.Publisher<String> stream = response.stream();
        assert stream != null;
        stream.subscribe(new Flow.Subscriber<>() {
            @Override public void onSubscribe(Flow.Subscription s) { s.request(Long.MAX_VALUE); }
            @Override public void onNext(String line) { events.add(line); }
            @Override public void onError(Throwable t) { latch.countDown(); }
            @Override public void onComplete() { latch.countDown(); }
        });

        assertThat(latch.await(3, TimeUnit.SECONDS)).isTrue();
        assertThat(events).hasSize(1);
        assertThat(events.get(0)).startsWith("data: ");
    }

    @Test
    void handle_malformedJson_returns500() {
        A2aHttpHandler handler = new A2aHttpHandler(createFakeServer());
        HttpRequest request = HttpRequest.newBuilder()
            .uri(URI.create("http://localhost/tasks/send"))
            .POST(HttpRequest.BodyPublishers.ofString("{not json"))
            .build();

        A2aHttpResponse response = handler.handle(request);

        assertThat(response.statusCode()).isEqualTo(500);
        assertThat(response.body()).contains("error");
    }

    @Test
    void handle_emptyRequestBody_returns500() {
        A2aHttpHandler handler = new A2aHttpHandler(createFakeServer());
        HttpRequest request = HttpRequest.newBuilder()
            .uri(URI.create("http://localhost/tasks/send"))
            .POST(HttpRequest.BodyPublishers.ofString(""))
            .build();

        A2aHttpResponse response = handler.handle(request);

        assertThat(response.statusCode()).isEqualTo(500);
        assertThat(response.body()).contains("error");
    }

    @Test
    void handle_unsupportedMethod_returns404() {
        A2aHttpHandler handler = new A2aHttpHandler(createFakeServer());

        HttpRequest deleteRequest = HttpRequest.newBuilder()
            .uri(URI.create("http://localhost/tasks/send"))
            .method("DELETE", HttpRequest.BodyPublishers.noBody())
            .build();
        assertThat(handler.handle(deleteRequest).statusCode()).isEqualTo(404);

        HttpRequest putRequest = HttpRequest.newBuilder()
            .uri(URI.create("http://localhost/tasks/t1"))
            .PUT(HttpRequest.BodyPublishers.noBody())
            .build();
        assertThat(handler.handle(putRequest).statusCode()).isEqualTo(404);
    }

    @Test
    void handle_serverException_returns500() {
        A2aHttpHandler handler = new A2aHttpHandler(createThrowingServer());

        HttpRequest cardRequest = HttpRequest.newBuilder()
            .uri(URI.create("http://localhost/.well-known/agent.json"))
            .GET()
            .build();
        assertThat(handler.handle(cardRequest).statusCode()).isEqualTo(500);

        HttpRequest sendRequest = HttpRequest.newBuilder()
            .uri(URI.create("http://localhost/tasks/send"))
            .POST(HttpRequest.BodyPublishers.ofString("{}"))
            .build();
        assertThat(handler.handle(sendRequest).statusCode()).isEqualTo(500);

        HttpRequest getRequest = HttpRequest.newBuilder()
            .uri(URI.create("http://localhost/tasks/t1"))
            .GET()
            .build();
        assertThat(handler.handle(getRequest).statusCode()).isEqualTo(500);

        HttpRequest cancelRequest = HttpRequest.newBuilder()
            .uri(URI.create("http://localhost/tasks/t1/cancel"))
            .POST(HttpRequest.BodyPublishers.noBody())
            .build();
        assertThat(handler.handle(cancelRequest).statusCode()).isEqualTo(500);

        HttpRequest subscribeRequest = HttpRequest.newBuilder()
            .uri(URI.create("http://localhost/tasks/t1/subscribe"))
            .GET()
            .build();
        assertThat(handler.handle(subscribeRequest).statusCode()).isEqualTo(500);
    }

    @Test
    void constructor_rejectsNulls() {
        assertThatNullPointerException().isThrownBy(() ->
            new A2aHttpHandler(null)
        ).withMessageContaining("server");

        assertThatNullPointerException().isThrownBy(() ->
            new A2aHttpHandler(createFakeServer(), null)
        ).withMessageContaining("mapper");
    }

    @Test
    void handle_rejectsNullRequest() {
        A2aHttpHandler handler = new A2aHttpHandler(createFakeServer());
        assertThatNullPointerException().isThrownBy(() -> handler.handle(null));
    }
}
