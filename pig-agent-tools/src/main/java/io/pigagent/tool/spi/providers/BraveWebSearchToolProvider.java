package io.pigagent.tool.spi.providers;

import io.pigagent.tool.spi.ToolContext;
import io.pigagent.tool.spi.ToolProvider;
import io.pigagent.tool.websearch.BraveWebSearchTool;

/** Auto-discovery provider for {@link BraveWebSearchTool}. */
public final class BraveWebSearchToolProvider implements ToolProvider {
    @Override
    public Object create(ToolContext context) {
        return new BraveWebSearchTool();
    }
}
