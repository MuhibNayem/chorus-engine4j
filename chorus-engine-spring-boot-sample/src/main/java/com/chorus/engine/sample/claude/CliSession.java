package com.chorus.engine.sample.claude;

import com.chorus.engine.agent.middleware.Middleware;
import com.chorus.engine.core.context.Message;
import com.chorus.engine.core.result.Result;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public final class CliSession {

    private final String runId = UUID.randomUUID().toString().substring(0, 8);
    private final List<Message> history = new ArrayList<>();
    private String agentsMd = "";
    private String claudeMd = "";

    private static final String DEFAULT_SYSTEM_PROMPT = """
            You are Chorus Code, a powerful AI coding assistant built with the chorus-engine4j framework.
            You are the Java-native equivalent of Claude Code.

            CAPABILITIES:
            - Read, write, and edit files in the workspace
            - Execute shell commands (with safety controls)
            - Read git history, diffs, and status
            - Search the web for documentation and solutions
            - Provide code review, debugging, and refactoring guidance

            TOOLS:
            - filesystem: read_file, write_file, list_directory, glob_search, file_info
            - git: git_status, git_diff, git_log, git_branch, git_show, git_blame
            - web_search: information retrieval when codebase knowledge is insufficient
            - shell: execute_command, execute_script (with safety allowlist)

            RULES:
            1. Read files before editing them
            2. Ask clarifying questions when requirements are ambiguous
            3. Follow existing code conventions in the project
            4. Never commit changes unless explicitly asked
            5. Run tests after making changes when possible
            6. Be concise and direct

            WORKFLOW:
            - For bugs: reproduce, diagnose, fix, verify
            - For features: understand, plan, implement, test
            - For exploration: find, read, explain, suggest

            Respond in a helpful, direct manner. Use tools proactively to get information.
            When you see /commands in chat, interpret them as user instructions.
            """;

    String getRunId() { return runId; }

    String getSystemPrompt() {
        StringBuilder sb = new StringBuilder(DEFAULT_SYSTEM_PROMPT);
        if (!agentsMd.isEmpty()) {
            sb.append("\n\n=== PROJECT CONTEXT (AGENTS.md) ===\n").append(agentsMd);
        }
        if (!claudeMd.isEmpty()) {
            sb.append("\n\n=== PROJECT CONTEXT (CLAUDE.md) ===\n").append(claudeMd);
        }
        return sb.toString();
    }

    List<Message> getHistory() { return Collections.unmodifiableList(history); }

    void addToHistory(Message message) { history.add(message); }

    void clearHistory() { history.clear(); }

    void setAgentsMd(String content) { this.agentsMd = content; }

    String getAgentsMd() { return agentsMd; }

    void setClaudeMd(String content) { this.claudeMd = content; }

    String getClaudeMd() { return claudeMd; }

    AgentMdMiddleware getAgentMdMiddleware() {
        return new AgentMdMiddleware(agentsMd, claudeMd);
    }

    public record SessionData(String runId, List<Message> history, String agentsMd, String claudeMd) {}

    SessionData toSessionData() {
        return new SessionData(runId, new ArrayList<>(history), agentsMd, claudeMd);
    }

    static CliSession fromSessionData(SessionData data) {
        CliSession session = new CliSession();
        session.history.addAll(data.history());
        session.agentsMd = data.agentsMd();
        session.claudeMd = data.claudeMd();
        return session;
    }

    static final class AgentMdMiddleware implements Middleware {
        private final String agentsMd;
        private final String claudeMd;

        AgentMdMiddleware(String agentsMd, String claudeMd) {
            this.agentsMd = agentsMd;
            this.claudeMd = claudeMd;
        }

        @Override
        public int priority() { return 100; }

        @Override
        public Result<String, MiddlewareError> extraSystemPrompt(
                String runId, List<Message> history, Map<String, Object> context) {
            StringBuilder sb = new StringBuilder();
            if (!agentsMd.isEmpty()) {
                sb.append("=== PROJECT CONTEXT (AGENTS.md) ===\n").append(agentsMd).append("\n\n");
            }
            if (!claudeMd.isEmpty()) {
                sb.append("=== PROJECT CONTEXT (CLAUDE.md) ===\n").append(claudeMd);
            }
            return Result.ok(sb.toString());
        }
    }
}
