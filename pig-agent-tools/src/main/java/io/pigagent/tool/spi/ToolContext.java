package io.pigagent.tool.spi;

import io.pigagent.core.outreach.NotificationService;
import io.pigagent.task.TaskManager;
import io.pigagent.tool.sandbox.SandboxPolicy;

import java.nio.file.Path;
import java.util.List;
import java.util.function.BooleanSupplier;

/**
 * Runtime dependencies a {@link ToolProvider} may need to build its tool. Immutable value holder;
 * fields not needed by a given provider may be {@code null}. Extend with new accessors as new tools
 * require new collaborators.
 */
public final class ToolContext {

    private final TaskManager taskManager;
    private final Path skillsDir;
    private final Path workspaceRoot;
    private final List<String> webAllowedHosts;
    private final SandboxPolicy sandboxPolicy;
    private final NotificationService notificationService;
    private final BooleanSupplier outreachEnabled;

    public ToolContext(TaskManager taskManager, Path skillsDir) {
        this(taskManager, skillsDir, null, null);
    }

    public ToolContext(TaskManager taskManager, Path skillsDir, Path workspaceRoot,
                       List<String> webAllowedHosts) {
        this(taskManager, skillsDir, workspaceRoot, webAllowedHosts, null);
    }

    public ToolContext(TaskManager taskManager, Path skillsDir, Path workspaceRoot,
                       List<String> webAllowedHosts, SandboxPolicy sandboxPolicy) {
        this(taskManager, skillsDir, workspaceRoot, webAllowedHosts, sandboxPolicy, null, null);
    }

    public ToolContext(TaskManager taskManager, Path skillsDir, Path workspaceRoot,
                       List<String> webAllowedHosts, SandboxPolicy sandboxPolicy,
                       NotificationService notificationService, BooleanSupplier outreachEnabled) {
        this.taskManager = taskManager;
        this.skillsDir = skillsDir;
        this.workspaceRoot = workspaceRoot;
        this.webAllowedHosts = webAllowedHosts == null ? List.of() : List.copyOf(webAllowedHosts);
        this.sandboxPolicy = sandboxPolicy;
        this.notificationService = notificationService;
        this.outreachEnabled = outreachEnabled == null ? () -> false : outreachEnabled;
    }

    /** The task manager (for the task tool); may be {@code null} in contexts that don't need it. */
    public TaskManager taskManager() {
        return taskManager;
    }

    /** The skills directory (for the skills tool); may be {@code null} in contexts that don't need it. */
    public Path skillsDir() {
        return skillsDir;
    }

    /**
     * The workspace root (for the file tool's credential-file blacklist); may be {@code null} in
     * contexts that don't need it, in which case the blacklist is disabled.
     */
    public Path workspaceRoot() {
        return workspaceRoot;
    }

    /**
     * Optional host allowlist for the web-fetch tool. Empty (never {@code null}) means "no
     * allowlist" — only the SSRF IP guard applies.
     */
    public List<String> webAllowedHosts() {
        return webAllowedHosts;
    }

    /**
     * The command-execution sandbox policy (for the shell tool); may be {@code null} in contexts that
     * don't need it, in which case {@code ShellTools} uses {@link SandboxPolicy#defaults()}.
     */
    public SandboxPolicy sandboxPolicy() {
        return sandboxPolicy;
    }

    /**
     * The proactive-outreach notification service (for the {@code notifyUser} tool); {@code null} when
     * outreach is not wired, in which case the provider registers no tool.
     */
    public NotificationService notificationService() {
        return notificationService;
    }

    /**
     * Whether proactive outreach is enabled — read live (a supplier) so the {@code notifyUser} tool's
     * availability tracks config. Never {@code null}; defaults to {@code false}.
     */
    public BooleanSupplier outreachEnabled() {
        return outreachEnabled;
    }
}
