package io.pigagent.tool.spi;

import io.pigagent.task.TaskManager;

import java.nio.file.Path;

/**
 * Runtime dependencies a {@link ToolProvider} may need to build its tool. Immutable value holder;
 * fields not needed by a given provider may be {@code null}. Extend with new accessors as new tools
 * require new collaborators.
 */
public final class ToolContext {

    private final TaskManager taskManager;
    private final Path skillsDir;

    public ToolContext(TaskManager taskManager, Path skillsDir) {
        this.taskManager = taskManager;
        this.skillsDir = skillsDir;
    }

    /** The task manager (for the task tool); may be {@code null} in contexts that don't need it. */
    public TaskManager taskManager() {
        return taskManager;
    }

    /** The skills directory (for the skills tool); may be {@code null} in contexts that don't need it. */
    public Path skillsDir() {
        return skillsDir;
    }
}
