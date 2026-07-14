package io.pigagent.tool.spi.providers;

import io.pigagent.tool.spi.ToolContext;
import io.pigagent.tool.spi.ToolProvider;
import io.pigagent.tool.webfetch.SmartWebFetchTool;

import java.util.List;

/** Auto-discovery provider for {@link SmartWebFetchTool}, wired with the optional host allowlist. */
public final class SmartWebFetchToolProvider implements ToolProvider {
    @Override
    public Object create(ToolContext context) {
        List<String> allowedHosts = context == null ? List.of() : context.webAllowedHosts();
        return new SmartWebFetchTool(allowedHosts);
    }
}
