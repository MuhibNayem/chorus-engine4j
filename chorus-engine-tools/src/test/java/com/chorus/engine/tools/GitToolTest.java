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

class GitToolTest {

    @TempDir
    Path tempDir;

    private void initGitRepo() throws Exception {
        run("git", "init");
        run("git", "config", "user.email", "test@test.com");
        run("git", "config", "user.name", "Test");
        Files.writeString(tempDir.resolve("file.txt"), "hello");
        run("git", "add", ".");
        run("git", "commit", "-m", "initial");
    }

    private void run(String... cmd) throws Exception {
        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.directory(tempDir.toFile());
        pb.redirectOutput(ProcessBuilder.Redirect.DISCARD);
        pb.redirectError(ProcessBuilder.Redirect.DISCARD);
        Process p = pb.start();
        p.waitFor();
    }

    @Test
    void gitLog_parsesCommits() throws Exception {
        initGitRepo();

        GitTool tool = new GitTool(tempDir);
        Result<ToolOutput, ToolError> result = tool.execute(
            Map.of("operation", "git_log", "maxCount", 5),
            CancellationToken.create());

        assertThat(result.isOk()).isTrue();
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> commits = (List<Map<String, Object>>) result.unwrap().structuredData().get("commits");
        assertThat(commits).hasSize(1);
        assertThat(commits.get(0).get("message")).isEqualTo("initial");
    }

    @Test
    void gitStatus_showsClean() throws Exception {
        initGitRepo();

        GitTool tool = new GitTool(tempDir);
        Result<ToolOutput, ToolError> result = tool.execute(
            Map.of("operation", "git_status"),
            CancellationToken.create());

        assertThat(result.isOk()).isTrue();
    }

    @Test
    void gitBranch_showsMasterOrMain() throws Exception {
        initGitRepo();

        GitTool tool = new GitTool(tempDir);
        Result<ToolOutput, ToolError> result = tool.execute(
            Map.of("operation", "git_branch"),
            CancellationToken.create());

        assertThat(result.isOk()).isTrue();
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> branches = (List<Map<String, Object>>) result.unwrap().structuredData().get("branches");
        assertThat(branches)
            .anySatisfy(b -> assertThat(b.get("name")).asString().matches("master|main"));
    }

    @Test
    void gitDiff_withPath() throws Exception {
        initGitRepo();
        Files.writeString(tempDir.resolve("file.txt"), "hello world");

        GitTool tool = new GitTool(tempDir);
        Result<ToolOutput, ToolError> result = tool.execute(
            Map.of("operation", "git_diff", "path", "file.txt"),
            CancellationToken.create());

        assertThat(result.isOk()).isTrue();
        assertThat(result.unwrap().content()).contains("hello world");
    }

    @Test
    void gitShow_requiresCommit() throws Exception {
        initGitRepo();

        GitTool tool = new GitTool(tempDir);
        Result<ToolOutput, ToolError> result = tool.execute(
            Map.of("operation", "git_show"),
            CancellationToken.create());

        assertThat(result.isErr()).isTrue();
        assertThat(result.unwrapErr()).isInstanceOf(ToolError.ValidationError.class);
    }

    @Test
    void notARepo_fails() {
        GitTool tool = new GitTool(tempDir);
        Result<ToolOutput, ToolError> result = tool.execute(
            Map.of("operation", "git_status"),
            CancellationToken.create());

        assertThat(result.isErr()).isTrue();
        assertThat(result.unwrapErr()).isInstanceOf(ToolError.ValidationError.class);
    }
}
