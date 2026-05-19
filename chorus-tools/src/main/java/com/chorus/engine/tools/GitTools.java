package com.chorus.engine.tools;

import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.util.concurrent.TimeUnit;

@Component
public class GitTools {

    private String runGit(String args) throws Exception {
        ProcessBuilder pb = new ProcessBuilder("bash", "-c", "git " + args);
        pb.directory(new java.io.File(System.getProperty("user.dir")));
        Process process = pb.start();
        boolean finished = process.waitFor(30, TimeUnit.SECONDS);
        if (!finished) {
            process.destroyForcibly();
            return "Error: git command timed out";
        }
        String stdout = new BufferedReader(new InputStreamReader(process.getInputStream()))
            .lines().collect(java.util.stream.Collectors.joining("\n"));
        String stderr = new BufferedReader(new InputStreamReader(process.getErrorStream()))
            .lines().collect(java.util.stream.Collectors.joining("\n"));
        int exitCode = process.exitValue();
        if (exitCode != 0) return "Error: " + stderr;
        return stdout.isEmpty() ? "(no output)" : stdout;
    }

    public String gitStatus() throws Exception { return runGit("status"); }
    public String gitDiff() throws Exception { return runGit("diff"); }
    public String gitLog(int count) throws Exception { return runGit("log -n " + count + " --oneline"); }
    public String gitBranch() throws Exception { return runGit("branch -a"); }
    public String gitCommit(String message) throws Exception {
        return runGit("commit -m \"" + message.replace("\"", "\\\"") + "\"");
    }
}
