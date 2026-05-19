package com.chorus.engine.a2a;

import com.chorus.engine.a2a.task.Part;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.*;

class MessagePartTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void textPart_serialization() throws Exception {
        Part part = new Part.TextPart("hello world");
        String json = mapper.writeValueAsString(part);

        assertThat(json).contains("\"type\":\"text\"");
        assertThat(json).contains("\"text\":\"hello world\"");

        Part parsed = mapper.readValue(json, Part.class);
        assertThat(parsed).isInstanceOf(Part.TextPart.class);
        assertThat(((Part.TextPart) parsed).text()).isEqualTo("hello world");
    }

    @Test
    void filePart_serialization() throws Exception {
        Part part = new Part.FilePart("https://example.com/file.txt", "text/plain");
        String json = mapper.writeValueAsString(part);

        assertThat(json).contains("\"type\":\"file\"");
        assertThat(json).contains("\"uri\":\"https://example.com/file.txt\"");
        assertThat(json).contains("\"mimeType\":\"text/plain\"");

        Part parsed = mapper.readValue(json, Part.class);
        assertThat(parsed).isInstanceOf(Part.FilePart.class);
        assertThat(((Part.FilePart) parsed).uri()).isEqualTo("https://example.com/file.txt");
        assertThat(((Part.FilePart) parsed).mimeType()).isEqualTo("text/plain");
    }

    @Test
    void filePart_nullMimeType() throws Exception {
        Part part = new Part.FilePart("https://example.com/file", null);
        String json = mapper.writeValueAsString(part);

        Part parsed = mapper.readValue(json, Part.class);
        assertThat(parsed).isInstanceOf(Part.FilePart.class);
        assertThat(((Part.FilePart) parsed).mimeType()).isNull();
    }

    @Test
    void dataPart_serialization() throws Exception {
        Part part = new Part.DataPart(Map.of("key", "value", "num", 42));
        String json = mapper.writeValueAsString(part);

        assertThat(json).contains("\"type\":\"data\"");

        Part parsed = mapper.readValue(json, Part.class);
        assertThat(parsed).isInstanceOf(Part.DataPart.class);
        assertThat(((Part.DataPart) parsed).data()).containsEntry("key", "value");
    }

    @Test
    void dataPart_defensivelyCopiesMap() {
        Map<String, Object> data = new HashMap<>();
        data.put("a", 1);
        Part.DataPart part = new Part.DataPart(data);
        data.put("b", 2);
        assertThat(part.data()).containsOnlyKeys("a");
    }

    @Test
    void polymorphicDeserialization_unknownTypeThrows() {
        String json = "{\"type\":\"unknown\",\"text\":\"hello\"}";
        assertThatThrownBy(() -> mapper.readValue(json, Part.class))
            .isInstanceOf(Exception.class);
    }
}
