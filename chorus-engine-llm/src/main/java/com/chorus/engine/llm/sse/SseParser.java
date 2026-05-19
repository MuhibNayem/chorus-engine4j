package com.chorus.engine.llm.sse;

import com.chorus.engine.core.result.Result;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.function.Consumer;

/**
 * Incremental Server-Sent Events parser.
 *
 * <p>Parses SSE streams line-by-line without materializing the entire response.
 * Handles {@code data:}, {@code event:}, {@code id:}, {@code retry:} fields.
 * Ignores comments ({@code :} lines).
 */
public final class SseParser {

    private final BufferedReader reader;
    private @Nullable String currentEventName = "message";
    private @Nullable String currentData;
    private @Nullable String currentId;

    public SseParser(@NonNull InputStream inputStream) {
        this.reader = new BufferedReader(new InputStreamReader(inputStream, StandardCharsets.UTF_8));
    }

    /**
     * Parse the stream, invoking the consumer for each complete event.
     * Blocks until the stream is closed or an unrecoverable error occurs.
     */
    public void parse(@NonNull Consumer<SseEvent> eventConsumer) throws IOException {
        String line;
        while ((line = reader.readLine()) != null) {
            if (line.startsWith("data: ")) {
                String data = line.substring(6);
                if (currentData == null) {
                    currentData = data;
                } else {
                    currentData += "\n" + data;
                }
            } else if (line.startsWith("event: ")) {
                currentEventName = line.substring(7).trim();
            } else if (line.startsWith("id: ")) {
                currentId = line.substring(4);
            } else if (line.startsWith(":")) {
                // SSE comment — ignore
            } else if (line.isEmpty()) {
                // Dispatch complete event
                if (currentData != null) {
                    eventConsumer.accept(new SseEvent(currentEventName, currentData, currentId));
                }
                currentData = null;
                currentEventName = "message";
            }
        }
        // Dispatch any remaining event
        if (currentData != null) {
            eventConsumer.accept(new SseEvent(currentEventName, currentData, currentId));
        }
    }

    /**
     * Parse a single event from a data line. Convenience for non-SSE JSON streaming.
     */
    public static @NonNull Result<SseEvent, String> parseLine(@NonNull String line) {
        if (line.startsWith("data: ")) {
            return Result.ok(new SseEvent("message", line.substring(6), null));
        }
        if (line.startsWith(":")) {
            return Result.err("comment");
        }
        return Result.err("unrecognized line format");
    }

    public record SseEvent(
        @NonNull String eventName,
        @NonNull String data,
        @Nullable String id
    ) {}
}
