package io.pigagent.tool.spi.providers;

import io.pigagent.tool.checklist.CheckListTool;
import io.pigagent.tool.spi.ToolContext;
import io.pigagent.tool.spi.ToolProvider;

/** Auto-discovery provider for {@link CheckListTool}. */
public final class CheckListToolProvider implements ToolProvider {
    @Override
    public Object create(ToolContext context) {
        return new CheckListTool();
    }
}
