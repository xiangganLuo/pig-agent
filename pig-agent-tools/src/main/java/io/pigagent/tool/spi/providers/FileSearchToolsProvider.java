package io.pigagent.tool.spi.providers;

import io.pigagent.tool.filesystem.FileSearchTools;
import io.pigagent.tool.spi.ToolContext;
import io.pigagent.tool.spi.ToolProvider;

import java.nio.file.Path;
import java.util.Set;

/**
 * Auto-discovery provider for {@link FileSearchTools} (the {@code searchFiles} / {@code findFiles}
 * tools). When a workspace root is present, the search tools are built with the same credential-file
 * blacklist as {@code FileSystemTools} (workspace {@code models.json} / {@code mcp.json} and their
 * {@code .bak} siblings) so search results never leak a credential file; otherwise they run without
 * a blacklist. Mirrors {@code FileSystemToolsProvider}.
 */
public final class FileSearchToolsProvider implements ToolProvider {
    @Override
    public Object create(ToolContext context) {
        Path root = context == null ? null : context.workspaceRoot();
        if (root == null) {
            return new FileSearchTools();
        }
        return new FileSearchTools(Set.of(
                root.resolve("models.json"),
                root.resolve("mcp.json"),
                root.resolve("models.json.bak"),
                root.resolve("mcp.json.bak")));
    }
}
