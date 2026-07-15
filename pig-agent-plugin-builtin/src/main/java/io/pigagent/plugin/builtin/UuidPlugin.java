package io.pigagent.plugin.builtin;

import io.pigagent.plugin.builtin.tool.UuidTool;
import io.pigagent.tool.spi.ToolContext;

import java.util.List;

/** Plugin contributing {@link UuidTool} (random UUID generation). */
public final class UuidPlugin extends AbstractToolPlugin {

    public UuidPlugin() {
        super("builtin:uuid");
    }

    @Override
    protected List<Object> createTools(ToolContext context) {
        return List.of(new UuidTool());
    }
}
