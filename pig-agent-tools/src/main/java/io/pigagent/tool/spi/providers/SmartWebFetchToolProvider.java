package io.pigagent.tool.spi.providers;

import io.pigagent.tool.spi.ToolContext;
import io.pigagent.tool.spi.ToolProvider;
import io.pigagent.tool.webfetch.SmartWebFetchTool;

/** Auto-discovery provider for {@link SmartWebFetchTool}. */
public final class SmartWebFetchToolProvider implements ToolProvider {
    @Override
    public Object create(ToolContext context) {
        return new SmartWebFetchTool();
    }
}
