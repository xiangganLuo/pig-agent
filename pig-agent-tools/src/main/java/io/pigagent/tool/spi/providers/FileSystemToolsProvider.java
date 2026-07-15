package io.pigagent.tool.spi.providers;

import io.pigagent.tool.filesystem.FileSystemTools;
import io.pigagent.tool.spi.ToolContext;
import io.pigagent.tool.spi.ToolProvider;

import java.nio.file.Path;
import java.util.Set;

/**
 * Auto-discovery provider for {@link FileSystemTools}. When a workspace root is present, the file
 * tools are built with a credential-file blacklist (workspace {@code models.json} / {@code mcp.json}
 * and their {@code .bak} siblings); otherwise the tools run without a blacklist.
 */
public final class FileSystemToolsProvider implements ToolProvider {
    @Override
    public Object create(ToolContext context) {
        Path root = context == null ? null : context.workspaceRoot();
        if (root == null) {
            return new FileSystemTools();
        }
        return new FileSystemTools(Set.of(
                root.resolve("models.json"),
                root.resolve("mcp.json"),
                root.resolve("models.json.bak"),
                root.resolve("mcp.json.bak")));
    }
}
