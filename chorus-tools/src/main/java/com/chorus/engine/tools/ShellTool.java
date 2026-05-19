package com.chorus.engine.tools;

import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.util.Set;
import java.util.concurrent.TimeUnit;

/**
 * Shell command execution with safety auditing and strict allow-list.
 */
@Component
public class ShellTool {

    private static final Set<String> ALLOWED_COMMANDS = Set.of(
        "ls", "cat", "echo", "grep", "find", "pwd", "wc", "head", "tail",
        "mkdir", "touch", "cp", "mv", "diff", "git", "npm", "node", "java",
        "gradle", "mvn", "python", "python3", "pip", "curl", "wget"
    );

    private static final Set<String> BLOCKED_PATTERNS = Set.of(
        "rm -rf /", "rm -rf /*", ":(){ :|:& };:", "> /dev/sda",
        "dd if=/dev/zero", "mkfs", "git push --force", "git reset --hard"
    );

    public String runCommand(String command) throws Exception {
        String trimmed = command.trim();

        for (String blocked : BLOCKED_PATTERNS) {
            if (trimmed.contains(blocked)) {
                return "Error: Command blocked by safety policy — contains dangerous pattern: " + blocked;
            }
        }

        String baseCommand = trimmed.split("\\s+")[0];
        if (!ALLOWED_COMMANDS.contains(baseCommand)) {
            return "Error: Command not in allow-list: " + baseCommand;
        }

        ProcessBuilder pb = new ProcessBuilder("bash", "-c", trimmed);
        pb.directory(new java.io.File(System.getProperty("user.dir")));
        Process process = pb.start();

        boolean finished = process.waitFor(60, TimeUnit.SECONDS);
        if (!finished) {
            process.destroyForcibly();
            return "Error: Command timed out after 60 seconds";
        }

        String stdout = new BufferedReader(new InputStreamReader(process.getInputStream()))
            .lines().collect(java.util.stream.Collectors.joining("\n"));
        String stderr = new BufferedReader(new InputStreamReader(process.getErrorStream()))
            .lines().collect(java.util.stream.Collectors.joining("\n"));

        int exitCode = process.exitValue();
        if (exitCode != 0) {
            return "Exit code " + exitCode + ":\n" + stderr + "\n" + stdout;
        }
        return stdout.isEmpty() ? "(no output)" : stdout;
    }
}
