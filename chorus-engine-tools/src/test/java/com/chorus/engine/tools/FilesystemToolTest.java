package com.chorus.engine.tools;

import com.chorus.engine.core.reactive.CancellationToken;
import com.chorus.engine.core.result.Result;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.*;

class FilesystemToolTest {

    @TempDir
    Path tempDir;

    @Test
    void readFile_success() throws Exception {
        Path file = tempDir.resolve("test.txt");
        Files.writeString(file, "hello world");

        FilesystemTool tool = new FilesystemTool(tempDir);
        Result<ToolOutput, ToolError> result = tool.execute(
            Map.of("operation", "read_file", "path", "test.txt"),
            CancellationToken.create());

        assertThat(result.isOk()).isTrue();
        assertThat(result.unwrap().content()).isEqualTo("hello world");
    }

    @Test
    void readFile_blocksTraversal() {
        FilesystemTool tool = new FilesystemTool(tempDir);
        Result<ToolOutput, ToolError> result = tool.execute(
            Map.of("operation", "read_file", "path", "../secret.txt"),
            CancellationToken.create());

        assertThat(result.isErr()).isTrue();
        assertThat(result.unwrapErr()).isInstanceOf(ToolError.SafetyBlocked.class);
    }

    @Test
    void writeFile_andListDirectory() throws Exception {
        FilesystemTool tool = new FilesystemTool(tempDir);

        Result<ToolOutput, ToolError> writeResult = tool.execute(
            Map.of("operation", "write_file", "path", "subdir/new.txt", "content", "new content"),
            CancellationToken.create());

        assertThat(writeResult.isOk()).isTrue();

        Result<ToolOutput, ToolError> listResult = tool.execute(
            Map.of("operation", "list_directory", "path", "."),
            CancellationToken.create());

        assertThat(listResult.isOk()).isTrue();
        assertThat(listResult.unwrap().structuredData()).containsKey("entries");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> entries = (List<Map<String, Object>>) listResult.unwrap().structuredData().get("entries");
        assertThat(entries).anySatisfy(e -> assertThat(e.get("name")).isEqualTo("subdir"));
    }

    @Test
    void globSearch_findsFiles() throws Exception {
        Files.createDirectories(tempDir.resolve("a"));
        Files.writeString(tempDir.resolve("a/foo.txt"), "1");
        Files.writeString(tempDir.resolve("a/bar.java"), "2");

        FilesystemTool tool = new FilesystemTool(tempDir);
        Result<ToolOutput, ToolError> result = tool.execute(
            Map.of("operation", "glob_search", "path", "a", "pattern", "*.txt"),
            CancellationToken.create());

        assertThat(result.isOk()).isTrue();
        @SuppressWarnings("unchecked")
        List<String> matches = (List<String>) result.unwrap().structuredData().get("matches");
        assertThat(matches).containsExactly("foo.txt");
    }

    @Test
    void fileInfo_returnsMetadata() throws Exception {
        Path file = tempDir.resolve("info.txt");
        Files.writeString(file, "content");

        FilesystemTool tool = new FilesystemTool(tempDir);
        Result<ToolOutput, ToolError> result = tool.execute(
            Map.of("operation", "file_info", "path", "info.txt"),
            CancellationToken.create());

        assertThat(result.isOk()).isTrue();
        assertThat(result.unwrap().structuredData())
            .containsEntry("name", "info.txt")
            .containsEntry("isDirectory", false)
            .containsEntry("size", 7L);
    }

    @Test
    void readFile_notFound() {
        FilesystemTool tool = new FilesystemTool(tempDir);
        Result<ToolOutput, ToolError> result = tool.execute(
            Map.of("operation", "read_file", "path", "missing.txt"),
            CancellationToken.create());

        assertThat(result.isErr()).isTrue();
        assertThat(result.unwrapErr()).isInstanceOf(ToolError.ValidationError.class);
    }
}
