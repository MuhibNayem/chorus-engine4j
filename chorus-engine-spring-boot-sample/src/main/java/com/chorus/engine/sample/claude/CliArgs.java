package com.chorus.engine.sample.claude;

import java.nio.file.Path;
import java.util.*;

record CliArgs(
        boolean nonInteractive,
        String prompt,
        String outputFormat,
        String modelFlag,
        List<String> allowedTools,
        int maxTurns,
        boolean planMode,
        String permissionMode,
        boolean verbose,
        Path workspace,
        boolean help
) {
    static CliArgs parse(String[] args) {
        boolean nonInteractive = false;
        String prompt = null;
        String outputFormat = "text";
        String modelFlag = null;
        List<String> allowedTools = new ArrayList<>();
        int maxTurns = 25;
        boolean planMode = false;
        String permissionMode = "default";
        boolean verbose = false;
        Path workspace = null;
        boolean help = false;

        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "-p" -> { nonInteractive = true; if (i + 1 < args.length) prompt = args[++i]; }
                case "--print" -> { nonInteractive = true; if (i + 1 < args.length) prompt = args[++i]; }
                case "--output-format" -> { if (i + 1 < args.length) outputFormat = args[++i]; }
                case "--model" -> { if (i + 1 < args.length) modelFlag = args[++i]; }
                case "--allowed-tools", "--allowedTools" -> {
                    if (i + 1 < args.length) allowedTools.addAll(Arrays.asList(args[++i].split(",")));
                }
                case "--max-turns", "--maxTurns" -> {
                    if (i + 1 < args.length) maxTurns = Integer.parseInt(args[++i]);
                }
                case "--plan" -> planMode = true;
                case "--permission-mode" -> {
                    if (i + 1 < args.length) permissionMode = args[++i];
                }
                case "--verbose" -> verbose = true;
                case "--workspace" -> { if (i + 1 < args.length) workspace = Path.of(args[++i]); }
                case "--help", "-h" -> help = true;
                default -> {
                    if (!args[i].startsWith("-")) {
                        nonInteractive = true;
                        prompt = args[i];
                    }
                }
            }
        }

        return new CliArgs(nonInteractive, prompt, outputFormat, modelFlag,
                allowedTools, maxTurns, planMode, permissionMode, verbose, workspace, help);
    }
}
