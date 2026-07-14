package io.pigagent.tool.spi;

import io.pigagent.task.TaskManager;

import java.nio.file.Path;
import java.util.List;

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

    public ToolContext(TaskManager taskManager, Path skillsDir) {
        this(taskManager, skillsDir, null, null);
    }

    public ToolContext(TaskManager taskManager, Path skillsDir, Path workspaceRoot,
                       List<String> webAllowedHosts) {
        this.taskManager = taskManager;
        this.skillsDir = skillsDir;
        this.workspaceRoot = workspaceRoot;
        this.webAllowedHosts = webAllowedHosts == null ? List.of() : List.copyOf(webAllowedHosts);
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
}
