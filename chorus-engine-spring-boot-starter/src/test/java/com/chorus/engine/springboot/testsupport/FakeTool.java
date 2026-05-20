package com.chorus.engine.springboot.testsupport;

import com.chorus.engine.core.reactive.CancellationToken;
import com.chorus.engine.core.result.Result;
import com.chorus.engine.tools.Tool;
import com.chorus.engine.tools.ToolError;
import com.chorus.engine.tools.ToolOutput;
import org.jspecify.annotations.NonNull;

import java.util.Map;

/**
 * Hand-written fake tool for testing.
 */
public class FakeTool implements Tool {

    private final @NonNull String name;
    private final @NonNull String description;
    private final @NonNull Map<String, Object> schema;

    public FakeTool(@NonNull String name, @NonNull String description, @NonNull Map<String, Object> schema) {
        this.name = name;
        this.description = description;
        this.schema = schema;
    }

    @Override
    public @NonNull String name() {
        return name;
    }

    @Override
    public @NonNull String description() {
        return description;
    }

    @Override
    public @NonNull Map<String, Object> parametersSchema() {
        return schema;
    }

    @Override
    public @NonNull Result<ToolOutput, ToolError> execute(@NonNull Map<String, Object> args, @NonNull CancellationToken token) {
        return Result.ok(ToolOutput.of("fake-result"));
    }
}
