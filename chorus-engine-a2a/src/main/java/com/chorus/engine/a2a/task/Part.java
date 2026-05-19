package com.chorus.engine.a2a.task;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

import java.util.Map;

@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "type")
@JsonSubTypes({
    @JsonSubTypes.Type(value = Part.TextPart.class, name = "text"),
    @JsonSubTypes.Type(value = Part.FilePart.class, name = "file"),
    @JsonSubTypes.Type(value = Part.DataPart.class, name = "data")
})
public sealed interface Part {

    record TextPart(@NonNull String text) implements Part {}

    record FilePart(@NonNull String uri, @Nullable String mimeType) implements Part {}

    record DataPart(@NonNull Map<String, Object> data) implements Part {
        public DataPart {
            data = Map.copyOf(data);
        }
    }
}
