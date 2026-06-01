package com.chorus.engine.sample.claude;

import com.chorus.engine.tools.Tool;
import com.chorus.engine.tools.ToolRegistry;

public final class CliRenderer {

    private static final String RESET = "\u001b[0m";
    private static final String BOLD = "\u001b[1m";
    private static final String DIM = "\u001b[2m";
    private static final String GREEN = "\u001b[32m";
    private static final String YELLOW = "\u001b[33m";
    private static final String BLUE = "\u001b[34m";
    private static final String CYAN = "\u001b[36m";
    private static final String RED = "\u001b[31m";
    private static final String MAGENTA = "\u001b[35m";

    private final boolean useColors;

    public CliRenderer() {
        this.useColors = System.console() != null
                && !"true".equals(System.getProperty("chorus.no-color"))
                && !"true".equals(System.getenv("NO_COLOR"));
    }

    void banner(String version) {
        if (useColors) {
            System.out.println(CYAN + "╔══════════════════════════════════════════════════════╗" + RESET);
            System.out.println(CYAN + "║" + BOLD + "    Chorus Code  v" + version + "                                " + CYAN + "║" + RESET);
            System.out.println(CYAN + "║" + DIM + "    Java-native AI coding assistant                    " + CYAN + "║" + RESET);
            System.out.println(CYAN + "║" + DIM + "    powered by chorus-engine4j                         " + CYAN + "║" + RESET);
            System.out.println(CYAN + "╚══════════════════════════════════════════════════════╝" + RESET);
        } else {
            System.out.println("=== Chorus Code v" + version + " ===");
        }
        System.out.println();
    }

    void prompt(String runId) {
        if (useColors) {
            System.out.print(BOLD + GREEN + "▶ " + RESET);
        } else {
            System.out.print("> ");
        }
    }

    void planPrompt() {
        if (useColors) {
            System.out.print(BOLD + MAGENTA + "Ⓟ " + RESET);
        } else {
            System.out.print("plan> ");
        }
    }

    void text(String msg) {
        System.out.print(msg);
    }

    void info(String msg) {
        if (useColors) {
            System.out.println(BLUE + "  ℹ " + msg + RESET);
        } else {
            System.out.println("  [INFO] " + msg);
        }
    }

    void success(String msg) {
        if (useColors) {
            System.out.println(GREEN + "  ✓ " + msg + RESET);
        } else {
            System.out.println("  [OK] " + msg);
        }
    }

    void warn(String msg) {
        if (useColors) {
            System.out.println(YELLOW + "  ⚠ " + msg + RESET);
        } else {
            System.out.println("  [WARN] " + msg);
        }
    }

    void error(String msg) {
        if (useColors) {
            System.out.println(RED + "  ✗ " + msg + RESET);
        } else {
            System.out.println("  [ERROR] " + msg);
        }
    }

    void dim(String msg) {
        if (useColors) {
            System.out.println(DIM + msg + RESET);
        } else {
            System.out.println(msg);
        }
    }

    void divider() {
        System.out.println(useColors ? (DIM + "  ──────────────────────────────────────────" + RESET) : "  ---");
    }

    void showHelp() {
        if (useColors) {
            System.out.println(CYAN + "\n  Chorus Code Commands:" + RESET);
        } else {
            System.out.println("\n  Chorus Code Commands:");
        }
        String[][] commands = {
                {"/help", "Show this help"},
                {"/exit, /quit", "Exit Chorus Code"},
                {"/clear", "Clear session history"},
                {"/plan", "Enter plan mode (analyze first, no edits)"},
                {"/auto", "Enter auto mode (make changes directly)"},
                {"/tools", "List available tools"},
                {"/git-status", "Show git workspace status"},
                {"/git-diff", "Show git diff"},
                {"/git-log", "Show recent git commits"},
                {"/git-branch", "List git branches"},
                {"/search <query>", "Search the web"},
                {"/save", "Save current session"},
                {"/load", "Load previous session"},
                {"/tokens", "Show estimated token count"},
                {"/cost", "Show cost estimate"},
                {"/review", "Review current code changes"},
                {"/debug", "Debug the current issue"},
                {"/test", "Run tests and fix failures"},
                {"/refactor", "Refactor code with analysis"},
                {"/docs", "Generate documentation"},
                {"/spawn <task>", "Spawn a sub-agent for parallel work"},
                {"/mcp", "MCP tool connection status"},
                {"/version", "Show version"},
        };
        for (String[] cmd : commands) {
            if (useColors) {
                System.out.printf("  " + GREEN + "%-24s" + RESET + " %s%n", cmd[0], cmd[1]);
            } else {
                System.out.printf("  %-24s %s%n", cmd[0], cmd[1]);
            }
        }
        System.out.println();
    }

    void showTools(ToolRegistry registry) {
        if (useColors) {
            System.out.println(CYAN + "\n  Available Tools (" + registry.allTools().size() + "):" + RESET);
        } else {
            System.out.println("\n  Available Tools (" + registry.allTools().size() + "):");
        }
        for (Tool tool : registry.allTools()) {
            if (useColors) {
                System.out.println("  " + GREEN + tool.name() + RESET + BOLD + " — " + RESET + tool.description());
            } else {
                System.out.println("  " + tool.name() + " — " + tool.description());
            }
        }
        System.out.println();
    }
}
