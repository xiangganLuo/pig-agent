package io.pigagent.tool.spi.providers;

import io.pigagent.tool.filesystem.FileSystemTools;
import io.pigagent.tool.spi.ToolContext;
import io.pigagent.tool.spi.ToolProvider;

/** Auto-discovery provider for {@link FileSystemTools}. */
public final class FileSystemToolsProvider implements ToolProvider {
    @Override
    public Object create(ToolContext context) {
        return new FileSystemTools();
    }
}
