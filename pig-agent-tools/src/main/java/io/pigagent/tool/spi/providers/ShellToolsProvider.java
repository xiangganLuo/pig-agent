package io.pigagent.tool.spi.providers;

import io.pigagent.tool.shell.ShellTools;
import io.pigagent.tool.spi.ToolContext;
import io.pigagent.tool.spi.ToolProvider;

/** Auto-discovery provider for {@link ShellTools}. */
public final class ShellToolsProvider implements ToolProvider {
    @Override
    public Object create(ToolContext context) {
        return new ShellTools();
    }
}
