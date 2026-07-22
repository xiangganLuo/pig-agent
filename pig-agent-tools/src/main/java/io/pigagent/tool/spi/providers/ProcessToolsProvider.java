package io.pigagent.tool.spi.providers;

import io.pigagent.tool.os.ProcessTools;
import io.pigagent.tool.spi.ToolContext;
import io.pigagent.tool.spi.ToolProvider;

/**
 * Auto-discovery provider for {@link ProcessTools} ({@code listProcesses}). Pure-Java, no runtime
 * dependencies, so the context is ignored.
 */
public final class ProcessToolsProvider implements ToolProvider {
    @Override
    public Object create(ToolContext context) {
        return new ProcessTools();
    }
}
