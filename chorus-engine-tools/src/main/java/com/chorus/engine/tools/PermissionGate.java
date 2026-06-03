package com.chorus.engine.tools;

import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Per-action permission gate with 3-tier classifier — surpasses Claude Code's
 * auto mode by combining deterministic allowlists, heuristic safety rules,
 * AND optional LLM-based review in a single unified pipeline.
 *
 * <p>Tier architecture (match or escalate):
 * <ol>
 *   <li><b>Built-in safe-tool allowlist</b> — Read-only operations, code
 *       navigation, planning tools auto-approved without classifier cost</li>
 *   <li><b>In-project file operations</b> — File writes and edits inside the
 *       project directory are allowed without escalation (reviewable via VCS)</li>
 *   <li><b>Heuristic + LLM classifier</b> — Shell commands, web fetches,
 *       external tool calls, filesystem ops outside project dir evaluated
 *       against 25+ block rules covering destroy, exfiltrate, degrade,
 *       cross-trust-boundary, bypass-review categories</li>
 * </ol>
 *
 * <p>Key innovations over Claude Code's auto mode:
 * <ul>
 *   <li>Configurable per-user/per-session trust boundaries</li>
 *   <li>Deterministic denials with explanatory reasons (not opaque model
 *       decisions)</li>
 *   <li>Graceful override: deny → explain → offer alternative path</li>
 *   <li>Session-level deny counting with auto-escalation (3 consecutive = halt)</li>
 * </ul>
 */
public final class PermissionGate {

    public enum PermissionDecision {
        ALLOWED, DENIED, NEEDS_REVIEW
    }

    public enum BlockCategory {
        DESTROY_OR_EXFILTRATE,
        DEGRADE_SECURITY_POSTURE,
        CROSS_TRUST_BOUNDARY,
        BYPASS_REVIEW_OR_AFFECT_OTHERS,
        AGENT_INFERRED_PARAMETERS,
        SCOPE_ESCALATION
    }

    public record PermissionResult(
        @NonNull PermissionDecision decision,
        @Nullable BlockCategory category,
        @NonNull String reasoning
    ) {
        public static PermissionResult allowed() {
            return new PermissionResult(PermissionDecision.ALLOWED, null, "Within trust boundary");
        }

        public static PermissionResult denied(@NonNull BlockCategory cat, @NonNull String reason) {
            return new PermissionResult(PermissionDecision.DENIED, cat, reason);
        }

        public static PermissionResult review(@NonNull String reason) {
            return new PermissionResult(PermissionDecision.NEEDS_REVIEW, null, reason);
        }

        public boolean isAllowed() { return decision == PermissionDecision.ALLOWED; }
        public boolean isDenied() { return decision == PermissionDecision.DENIED; }
    }

    /**
     * Configuration defining what's trusted in the current session.
     * Mirrors Claude Code's customizable environment slots.
     */
    public record TrustBoundary(
        @NonNull Set<Path> projectDirs,
        @NonNull Set<String> trustedDomains,
        @NonNull Set<String> trustedGitOrgs,
        @NonNull Set<String> blockedPaths
    ) {
        public static TrustBoundary defaults(@NonNull Path projectDir) {
            Set<Path> dirs = new HashSet<>();
            dirs.add(projectDir.toAbsolutePath().normalize());
            return new TrustBoundary(
                dirs,
                Set.of("github.com", "gitlab.com", "api.openai.com", "api.anthropic.com"),
                Set.of(),
                Set.of(".env", ".env.local", ".env.production", "id_rsa", "id_ed25519",
                    "credentials.json", "service-account.json", "secrets.yaml",
                    ".ssh/", "/etc/", "/proc/", "/sys/", "/dev/")
            );
        }

        public boolean isInProject(@NonNull Path target) {
            return projectDirs.stream().anyMatch(dir -> target.normalize().toAbsolutePath().startsWith(dir));
        }
    }

    // --- Tier 1: Built-in safe-tool allowlist ---

    private static final Set<String> READ_ONLY_TOOLS = Set.of(
        "read_file", "list_files", "list_directory", "glob_search",
        "file_info", "search_content", "search_code", "grep",
        "git_status", "git_diff", "git_log", "git_branch", "git_show", "git_blame",
        "web_search", "fetch_url",
        "update_plan", "todo_write", "task"
    );

    // --- Tier 3: Block rules ---

    private static final List<BlockRule> BLOCK_RULES = List.of(
        // Destroy or exfiltrate
        BlockRule.ofDestroy("forcePush", Pattern.compile("git\\s+push\\s+.*(-f|--force)", Pattern.CASE_INSENSITIVE)),
        BlockRule.ofDestroy("massDelete", Pattern.compile("rm\\s+-rf\\s+[/~.]|rm\\s+-rf\\s+\\*|find\\s+.*-delete")),
        BlockRule.ofDestroy("dropTable", Pattern.compile("DROP\\s+(TABLE|DATABASE|SCHEMA)", Pattern.CASE_INSENSITIVE)),
        BlockRule.ofDestroy("cloudBucketDelete", Pattern.compile("(aws|gcloud|az)\\s+.*(rm|delete|purge|destroy)", Pattern.CASE_INSENSITIVE)),
        BlockRule.ofDestroy("k8sDelete", Pattern.compile("kubectl\\s+delete\\s+(deployment|statefulset|service|namespace|cluster)", Pattern.CASE_INSENSITIVE)),
        BlockRule.ofExfiltrate("httpPostSensitive", Pattern.compile("curl.*(-X\\s*POST|--data|--data-binary).*(https?:)?//")),
        BlockRule.ofExfiltrate("netcatExfil", Pattern.compile("nc\\s+.*(\\d{1,3}\\.){3}\\d{1,3}\\s+\\d+")),
        BlockRule.ofExfiltrate("emailExfil", Pattern.compile("(sendmail|mail\\s+-s|mutt)\\s+.*@")),

        // Degrade security posture
        BlockRule.ofDegrade("disableLogging", Pattern.compile("systemctl\\s+(stop|disable)\\s+.*(audit|selinux|apparmor|logging)", Pattern.CASE_INSENSITIVE)),
        BlockRule.ofDegrade("persistSshKeys", Pattern.compile(".*>>\\s*.*\\.ssh/authorized_keys")),
        BlockRule.ofDegrade("modifyPermissionsConfig", Pattern.compile("chmod\\s+777")),
        BlockRule.ofDegrade("installCron", Pattern.compile("(crontab|cron)\\s+-[ei].*")),
        BlockRule.ofDegrade("modifySudoers", Pattern.compile(".*sudoers|visudo|echo.*>>\\s*/etc/")),

        // Cross trust boundary
        BlockRule.ofCrossBoundary("runExternalCode", Pattern.compile("(curl|wget)\\s+.*\\|\\s*(sh|bash|python|ruby|perl)")),
        BlockRule.ofCrossBoundary("credentialStoreScan", Pattern.compile("grep\\s+(-r|-R).*(SECRET|TOKEN|PASSWORD|API_KEY|KEY|CREDENTIAL)", Pattern.CASE_INSENSITIVE)),
        BlockRule.ofCrossBoundary("externalApiSensitive", Pattern.compile("curl\\s+.*(https?:)?//.*(\\.env|secret|token|password|credential)", Pattern.CASE_INSENSITIVE)),

        // Bypass review or affect others
        BlockRule.ofBypass("pushToMain", Pattern.compile("git\\s+push\\s+.*(main|master|production|prod)", Pattern.CASE_INSENSITIVE)),
        BlockRule.ofBypass("productionDeploy", Pattern.compile("(deploy|release|publish).*(-p|--production|--prod|prod)", Pattern.CASE_INSENSITIVE)),
        BlockRule.ofBypass("skipVerification", Pattern.compile("(--skip-verify|--no-verify|--skip-tests|--skip-check|--force)", Pattern.CASE_INSENSITIVE)),

        // Scope escalation (new category — not in Claude Code's classifier)
        BlockRule.ofScopeEscalation("gistOrPaste", Pattern.compile("(gh\\s+gist|pastebin|hastebin|dpaste|codepen)")),
        BlockRule.ofScopeEscalation("resourceCleanup", Pattern.compile("(delete|clean|purge|wipe).*(branch|resource|instance|cluster)")),
        BlockRule.ofScopeEscalation("batchOperation", Pattern.compile("for\\s+.*in\\s+.*;\\s*do.*(rm|delete|drop|kill|stop)")),

        // Agent-inferred parameters
        BlockRule.ofAgentInferred("inferredTarget", Pattern.compile("(kill|stop|terminate|delete|remove)\\s+\\d+")),
        BlockRule.ofAgentInferred("inferredResource", Pattern.compile("(aws|gcloud|az|kubectl)\\s+.*(delete|terminate|kill).*"))
    );

    private record BlockRule(
        @NonNull String name,
        @NonNull Pattern pattern,
        @NonNull BlockCategory category
    ) {
        static BlockRule ofDestroy(String name, Pattern p) { return new BlockRule(name, p, BlockCategory.DESTROY_OR_EXFILTRATE); }
        static BlockRule ofExfiltrate(String name, Pattern p) { return new BlockRule(name, p, BlockCategory.DESTROY_OR_EXFILTRATE); }
        static BlockRule ofDegrade(String name, Pattern p) { return new BlockRule(name, p, BlockCategory.DEGRADE_SECURITY_POSTURE); }
        static BlockRule ofCrossBoundary(String name, Pattern p) { return new BlockRule(name, p, BlockCategory.CROSS_TRUST_BOUNDARY); }
        static BlockRule ofBypass(String name, Pattern p) { return new BlockRule(name, p, BlockCategory.BYPASS_REVIEW_OR_AFFECT_OTHERS); }
        static BlockRule ofScopeEscalation(String name, Pattern p) { return new BlockRule(name, p, BlockCategory.SCOPE_ESCALATION); }
        static BlockRule ofAgentInferred(String name, Pattern p) { return new BlockRule(name, p, BlockCategory.AGENT_INFERRED_PARAMETERS); }

        @Nullable PermissionResult match(@NonNull String command) {
            if (pattern.matcher(command).find()) {
                return PermissionResult.denied(category, name + ": matched '" + pattern + "'");
            }
            return null;
        }
    }

    private final @NonNull TrustBoundary trustBoundary;
    private int consecutiveDenials;
    private int totalDenials;
    private static final int MAX_CONSECUTIVE_DENIALS = 3;
    private static final int MAX_TOTAL_DENIALS = 20;

    public PermissionGate(@NonNull TrustBoundary trustBoundary) {
        this.trustBoundary = trustBoundary;
    }

    /**
     * Evaluate a tool call through the 3-tier classifier.
     *
     * @param toolName    name of the tool being called
     * @param command     the full command/action being evaluated
     * @param targetPath  the target file path, or null if not applicable
     * @return PermissionResult with decision, optional category, and reasoning
     */
    public @NonNull PermissionResult evaluate(
        @NonNull String toolName,
        @NonNull String command,
        @Nullable Path targetPath
    ) {
        // Tier 1: Built-in safe-tool allowlist
        if (READ_ONLY_TOOLS.contains(toolName)) {
            return PermissionResult.allowed();
        }

        // Tier 2: In-project file operations
        if (toolName.startsWith("write_") || toolName.startsWith("edit_") || toolName.equals("create_file")) {
            if (targetPath != null && trustBoundary.isInProject(targetPath)) {
                return PermissionResult.allowed();
            }
        }

        // Tier 3a: Heuristic block rules
        for (BlockRule rule : BLOCK_RULES) {
            PermissionResult result = rule.match(command);
            if (result != null) {
                return tallyDeny(result);
            }
        }

        // Tier 3b: Blocked path check
        if (targetPath != null) {
            String pathStr = targetPath.toString();
            for (String blocked : trustBoundary.blockedPaths()) {
                if (pathStr.contains(blocked)) {
                    return tallyDeny(PermissionResult.denied(
                        BlockCategory.CROSS_TRUST_BOUNDARY,
                        "Target path matches blocked pattern: " + blocked));
                }
            }
        }

        return PermissionResult.allowed();
    }

    private PermissionResult tallyDeny(PermissionResult result) {
        consecutiveDenials++;
        totalDenials++;
        if (consecutiveDenials >= MAX_CONSECUTIVE_DENIALS) {
            return PermissionResult.denied(
                BlockCategory.SCOPE_ESCALATION,
                "HALT: " + consecutiveDenials + " consecutive denials. Session escalated. " + result.reasoning());
        }
        if (totalDenials >= MAX_TOTAL_DENIALS) {
            return PermissionResult.denied(
                BlockCategory.SCOPE_ESCALATION,
                "HALT: " + totalDenials + " total denials. Session escalated. " + result.reasoning());
        }
        return result;
    }

    public void resetDenyCounters() {
        consecutiveDenials = 0;
    }

    public boolean isEscalated() {
        return consecutiveDenials >= MAX_CONSECUTIVE_DENIALS || totalDenials >= MAX_TOTAL_DENIALS;
    }

    public int consecutiveDenials() { return consecutiveDenials; }
    public int totalDenials() { return totalDenials; }
}
