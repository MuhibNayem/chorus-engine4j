package com.chorus.engine.llm.sse;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.*;

class SseParserTest {

    @Test
    void parse_basic_events() throws Exception {
        String sse = """
            data: hello
            
            data: world
            
            data: [DONE]
            
            """;

        SseParser parser = new SseParser(new ByteArrayInputStream(sse.getBytes(StandardCharsets.UTF_8)));
        List<SseParser.SseEvent> events = new ArrayList<>();
        parser.parse(events::add);

        assertThat(events).hasSize(3);
        assertThat(events.get(0).data()).isEqualTo("hello");
        assertThat(events.get(1).data()).isEqualTo("world");
        assertThat(events.get(2).data()).isEqualTo("[DONE]");
    }

    @Test
    void parse_multiline_data() throws Exception {
        String sse = """
            data: line1
            data: line2
            
            """;

        SseParser parser = new SseParser(new ByteArrayInputStream(sse.getBytes(StandardCharsets.UTF_8)));
        List<SseParser.SseEvent> events = new ArrayList<>();
        parser.parse(events::add);

        assertThat(events).hasSize(1);
        assertThat(events.get(0).data()).isEqualTo("line1\nline2");
    }

    @Test
    void parse_with_event_name() throws Exception {
        String sse = """
            event: message
            data: hello
            
            """;

        SseParser parser = new SseParser(new ByteArrayInputStream(sse.getBytes(StandardCharsets.UTF_8)));
        List<SseParser.SseEvent> events = new ArrayList<>();
        parser.parse(events::add);

        assertThat(events.get(0).eventName()).isEqualTo("message");
        assertThat(events.get(0).data()).isEqualTo("hello");
    }

    @Test
    void parse_ignores_comments() throws Exception {
        String sse = """
            : this is a comment
            data: real data
            
            """;

        SseParser parser = new SseParser(new ByteArrayInputStream(sse.getBytes(StandardCharsets.UTF_8)));
        List<SseParser.SseEvent> events = new ArrayList<>();
        parser.parse(events::add);

        assertThat(events).hasSize(1);
        assertThat(events.get(0).data()).isEqualTo("real data");
    }

    @Test
    void parseLine_parses_data_prefix() {
        var result = SseParser.parseLine("data: hello");
        assertThat(result.isOk()).isTrue();
        assertThat(result.unwrap().data()).isEqualTo("hello");
    }

    @Test
    void parseLine_ignores_comments() {
        var result = SseParser.parseLine(": comment");
        assertThat(result.isErr()).isTrue();
    }
}
