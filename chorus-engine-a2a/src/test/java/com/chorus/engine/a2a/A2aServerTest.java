package com.chorus.engine.a2a;

import com.chorus.engine.a2a.card.AgentCard;
import com.chorus.engine.a2a.card.Authentication;
import com.chorus.engine.a2a.card.Capabilities;
import com.chorus.engine.a2a.card.Skill;
import com.chorus.engine.a2a.server.A2aHttpHandler;
import com.chorus.engine.a2a.server.A2aHttpResponse;
import com.chorus.engine.a2a.server.A2aServer;
import com.chorus.engine.a2a.server.TaskUpdate;
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

class A2aServerTest {

    private final ObjectMapper mapper = new ObjectMapper();

    private A2aServer createStubServer() {
        return new A2aServer() {
            @Override
            public AgentCard getAgentCard() {
                return new AgentCard("test-agent", "A test agent", "http://localhost", "1.0",
                    new Capabilities(true, false),
                    new Authentication(List.of("Bearer")),
                    List.of(new Skill("skill-1", "Test Skill", "Does testing", List.of("test"))));
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
                    subscriber.onNext(new TaskUpdate(taskId, Task.Status.COMPLETED, null, null));
                    subscriber.onComplete();
                };
            }
        };
    }

    @Test
    void handle_wellKnownAgentJson() throws Exception {
        A2aHttpHandler handler = new A2aHttpHandler(createStubServer());
        HttpRequest request = HttpRequest.newBuilder()
            .uri(URI.create("http://localhost/.well-known/agent.json"))
            .GET()
            .build();

        A2aHttpResponse response = handler.handle(request);

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).isNotNull();
        AgentCard card = mapper.readValue(response.body(), AgentCard.class);
        assertThat(card.name()).isEqualTo("test-agent");
        assertThat(response.headers().get("Content-Type")).containsExactly("application/json");
    }

    @Test
    void handle_tasksSend() throws Exception {
        A2aHttpHandler handler = new A2aHttpHandler(createStubServer());
        Task task = new Task("t1", "s1", Task.Status.SUBMITTED,
            List.of(new Message(Message.Role.USER, List.of(new Part.TextPart("hi")), null)),
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
    void handle_tasksGet() throws Exception {
        A2aHttpHandler handler = new A2aHttpHandler(createStubServer());
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
    void handle_tasksCancel() throws Exception {
        A2aHttpHandler handler = new A2aHttpHandler(createStubServer());
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
    void handle_tasksSubscribe_sseStream() throws Exception {
        A2aHttpHandler handler = new A2aHttpHandler(createStubServer());
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
        stream.subscribe(new Flow.Subscriber<String>() {
            @Override public void onSubscribe(Flow.Subscription s) { s.request(Long.MAX_VALUE); }
            @Override public void onNext(String line) { events.add(line); }
            @Override public void onError(Throwable t) { latch.countDown(); }
            @Override public void onComplete() { latch.countDown(); }
        });

        assertThat(latch.await(5, TimeUnit.SECONDS)).isTrue();
        assertThat(events).hasSize(2);
        assertThat(events.get(0)).startsWith("data: ");
        assertThat(events.get(1)).startsWith("data: ");

        TaskUpdate first = mapper.readValue(events.get(0).substring(6), TaskUpdate.class);
        assertThat(first.taskId()).isEqualTo("t1");
        assertThat(first.status()).isEqualTo(Task.Status.WORKING);
    }

    @Test
    void handle_notFound() throws Exception {
        A2aHttpHandler handler = new A2aHttpHandler(createStubServer());
        HttpRequest request = HttpRequest.newBuilder()
            .uri(URI.create("http://localhost/unknown"))
            .GET()
            .build();

        A2aHttpResponse response = handler.handle(request);

        assertThat(response.statusCode()).isEqualTo(404);
    }

    @Test
    void handle_tasksSend_invalidPath_returns404() throws Exception {
        A2aHttpHandler handler = new A2aHttpHandler(createStubServer());
        HttpRequest request = HttpRequest.newBuilder()
            .uri(URI.create("http://localhost/tasks/send/extra"))
            .POST(HttpRequest.BodyPublishers.noBody())
            .build();

        A2aHttpResponse response = handler.handle(request);
        assertThat(response.statusCode()).isEqualTo(404);
    }
}
