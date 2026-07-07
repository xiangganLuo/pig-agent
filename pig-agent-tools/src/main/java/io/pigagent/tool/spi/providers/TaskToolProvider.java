package io.pigagent.tool.spi.providers;

import io.pigagent.tool.spi.ToolContext;
import io.pigagent.tool.spi.ToolProvider;
import io.pigagent.tool.task.TaskTool;

/** Auto-discovery provider for {@link TaskTool} (needs the task manager from the context). */
public final class TaskToolProvider implements ToolProvider {
    @Override
    public Object create(ToolContext context) {
        return new TaskTool(context.taskManager());
    }
}
