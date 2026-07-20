package io.pigagent.tool.spi.providers;

import io.pigagent.tool.filesystem.FileSearchTools;
import io.pigagent.tool.spi.ToolContext;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Wiring test: the provider builds a {@link FileSearchTools} whether or not a workspace root is
 * present on the context (backward compatible), mirroring {@code FileSystemToolsProvider}.
 */
class FileSearchToolsProviderTest {

    private final FileSearchToolsProvider provider = new FileSearchToolsProvider();

    @Test
    void buildsWithoutWorkspaceRoot() {
        ToolContext ctx = new ToolContext(null, null);

        assertThat(provider.create(ctx)).isInstanceOf(FileSearchTools.class);
    }

    @Test
    void buildsWithWorkspaceRoot(@TempDir Path ws) {
        ToolContext ctx = ToolContext.builder().workspaceRoot(ws).build();

        assertThat(provider.create(ctx)).isInstanceOf(FileSearchTools.class);
    }

    @Test
    void buildsWhenContextNull() {
        assertThat(provider.create(null)).isInstanceOf(FileSearchTools.class);
    }
}
