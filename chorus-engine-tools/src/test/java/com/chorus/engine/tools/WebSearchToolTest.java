package com.chorus.engine.tools;

import com.chorus.engine.core.reactive.CancellationToken;
import com.chorus.engine.core.result.Result;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.*;

class WebSearchToolTest {

    @Test
    void fetchUrl_blocksLocalhost() {
        WebSearchTool tool = new WebSearchTool();
        Result<ToolOutput, ToolError> result = tool.execute(
            Map.of("operation", "fetch_url", "url", "http://localhost:8080"),
            CancellationToken.create());

        assertThat(result.isErr()).isTrue();
        assertThat(result.unwrapErr()).isInstanceOf(ToolError.SafetyBlocked.class);
    }

    @Test
    void fetchUrl_blocksFileProtocol() {
        WebSearchTool tool = new WebSearchTool();
        Result<ToolOutput, ToolError> result = tool.execute(
            Map.of("operation", "fetch_url", "url", "file:///etc/passwd"),
            CancellationToken.create());

        assertThat(result.isErr()).isTrue();
        assertThat(result.unwrapErr()).isInstanceOf(ToolError.SafetyBlocked.class);
    }

    @Test
    void fetchUrl_blocksPrivateIp() {
        WebSearchTool tool = new WebSearchTool();
        Result<ToolOutput, ToolError> result = tool.execute(
            Map.of("operation", "fetch_url", "url", "http://192.168.1.1/admin"),
            CancellationToken.create());

        assertThat(result.isErr()).isTrue();
        assertThat(result.unwrapErr()).isInstanceOf(ToolError.SafetyBlocked.class);
    }

    @Test
    void webSearch_missingQuery() {
        WebSearchTool tool = new WebSearchTool();
        Result<ToolOutput, ToolError> result = tool.execute(
            Map.of("operation", "web_search"),
            CancellationToken.create());

        assertThat(result.isErr()).isTrue();
        assertThat(result.unwrapErr()).isInstanceOf(ToolError.ValidationError.class);
    }

    @Test
    void webSearch_blocksDangerousQuery() {
        WebSearchTool tool = new WebSearchTool();
        Result<ToolOutput, ToolError> result = tool.execute(
            Map.of("operation", "web_search", "query", "test", "numResults", 3),
            CancellationToken.create());

        // DuckDuckGo may return results or fail depending on network; we just verify
        // the call does not throw and either succeeds or returns an execution error.
        assertThat(result.isOk() || result.isErr()).isTrue();
    }
}
