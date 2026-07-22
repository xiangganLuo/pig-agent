package io.pigagent.tool.spi.providers;

import io.pigagent.tool.os.SystemInfoTools;
import io.pigagent.tool.spi.ToolContext;
import io.pigagent.tool.spi.ToolProvider;

/**
 * Auto-discovery provider for {@link SystemInfoTools} ({@code systemInfo} / {@code diskUsage}). The
 * tools are pure-Java and need no runtime dependencies, so the context is ignored.
 */
public final class SystemInfoToolsProvider implements ToolProvider {
    @Override
    public Object create(ToolContext context) {
        return new SystemInfoTools();
    }
}
