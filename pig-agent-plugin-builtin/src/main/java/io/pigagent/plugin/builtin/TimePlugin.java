package io.pigagent.plugin.builtin;

import io.pigagent.plugin.builtin.tool.TimeTool;
import io.pigagent.tool.spi.ToolContext;

import java.util.List;

/** Plugin contributing {@link TimeTool} (current time, timezone conversion, epoch ↔ ISO). */
public final class TimePlugin extends AbstractToolPlugin {

    public TimePlugin() {
        super("builtin:time");
    }

    @Override
    protected List<Object> createTools(ToolContext context) {
        return List.of(new TimeTool());
    }
}
