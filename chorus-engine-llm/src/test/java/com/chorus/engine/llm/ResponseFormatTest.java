package com.chorus.engine.llm;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.*;

class ResponseFormatTest {

    @Test
    void text_factory() {
        ResponseFormat format = ResponseFormat.text();

        assertThat(format.type()).isEqualTo(ResponseFormat.Type.TEXT);
        assertThat(format.jsonSchema()).isNull();
    }

    @Test
    void json_factory() {
        ResponseFormat format = ResponseFormat.json();

        assertThat(format.type()).isEqualTo(ResponseFormat.Type.JSON_OBJECT);
        assertThat(format.jsonSchema()).isNull();
    }

    @Test
    void jsonSchema_factory() {
        Map<String, Object> schema = Map.of("type", "object", "properties", Map.of("name", Map.of("type", "string")));
        ResponseFormat format = ResponseFormat.jsonSchema(schema);

        assertThat(format.type()).isEqualTo(ResponseFormat.Type.JSON_SCHEMA);
        assertThat(format.jsonSchema()).containsEntry("type", "object");
    }

    @Test
    void jsonSchema_defensive_copy() {
        java.util.HashMap<String, Object> mutable = new java.util.HashMap<>();
        mutable.put("key", "value");
        ResponseFormat format = ResponseFormat.jsonSchema(mutable);
        mutable.put("key", "changed");

        assertThat(format.jsonSchema()).containsEntry("key", "value");
    }

    @Test
    void null_type_rejected() {
        assertThatThrownBy(() -> new ResponseFormat(null, null))
            .isInstanceOf(NullPointerException.class);
    }

    @Test
    void null_schema_for_jsonSchema_throws() {
        assertThatThrownBy(() -> ResponseFormat.jsonSchema(null))
            .isInstanceOf(NullPointerException.class);
    }
}
