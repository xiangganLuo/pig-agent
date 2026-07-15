package io.pigagent.plugin.collection;

import io.pigagent.plugin.collection.tool.UuidTool;
import io.pigagent.tool.spi.ToolContext;

import java.util.List;

/** Plugin contributing {@link UuidTool} (random UUID generation). */
public final class UuidPlugin extends AbstractToolPlugin {

    public UuidPlugin() {
        super("collection:uuid");
    }

    @Override
    protected List<Object> createTools(ToolContext context) {
        return List.of(new UuidTool());
    }
}
