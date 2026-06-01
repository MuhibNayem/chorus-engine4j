package com.chorus.engine.sample.claude;

import com.chorus.engine.agent.middleware.Middleware;
import com.chorus.engine.core.context.Message;
import com.chorus.engine.core.result.Result;

import java.util.List;
import java.util.Map;
import java.util.Set;

final class PermissionController {

    enum Mode { DEFAULT, ACCEPT_EDITS, AUTO, STRICT, BYPASS }

    private Mode mode = Mode.DEFAULT;
    private final Set<String> allowedTools = new java.util.concurrent.ConcurrentHashMap<String, Boolean>() {{
        put("git", true);
        put("filesystem", true);
        put("web_search", true);
    }}.keySet();

    void setMode(Mode mode) { this.mode = mode; }
    void setMode(String modeStr) {
        this.mode = switch (modeStr.toLowerCase()) {
            case "auto" -> Mode.AUTO;
            case "acceptedits", "accept-edits" -> Mode.ACCEPT_EDITS;
            case "strict" -> Mode.STRICT;
            case "bypass" -> Mode.BYPASS;
            default -> Mode.DEFAULT;
        };
    }
    Mode getMode() { return mode; }

    void allow(String tool) { ((java.util.Set<String>) allowedTools).add(tool); }
    void deny(String tool) { ((java.util.Set<String>) allowedTools).remove(tool); }

    boolean needsApproval(String toolName) {
        return switch (mode) {
            case BYPASS -> false;
            case STRICT -> true;
            case ACCEPT_EDITS -> !allowedTools.contains(toolName)
                    && !toolName.equals("filesystem")
                    && !toolName.equals("git");
            case DEFAULT -> !allowedTools.contains(toolName);
            case AUTO -> false;
        };
    }

    Middleware toMiddleware() {
        return new PermissionMiddleware();
    }

    private class PermissionMiddleware implements Middleware {
        @Override public int priority() { return 200; }

        @Override
        public Result<ToolDecision, MiddlewareError> beforeTool(
                String runId, String toolName, Map<String, Object> arguments, Map<String, Object> context) {
            if (mode == Mode.AUTO && isDangerous(toolName, arguments)) {
                return Result.ok(new ToolDecision(false, arguments, "Auto-blocked dangerous operation"));
            }
            return Result.ok(new ToolDecision(true, arguments, null));
        }

        private boolean isDangerous(String toolName, Map<String, Object> args) {
            if ("shell".equals(toolName)) {
                String cmd = args.get("command") instanceof String s ? s : "";
                return cmd.contains("rm -rf") || cmd.contains("sudo") || cmd.contains("fork")
                        || cmd.contains("> /dev/") || cmd.contains("mkfs");
            }
            return false;
        }
    }
}
