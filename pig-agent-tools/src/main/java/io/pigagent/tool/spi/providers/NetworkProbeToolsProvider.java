package io.pigagent.tool.spi.providers;

import io.pigagent.tool.os.NetworkProbeTools;
import io.pigagent.tool.spi.ToolContext;
import io.pigagent.tool.spi.ToolProvider;

/**
 * Auto-discovery provider for {@link NetworkProbeTools} ({@code resolveHost} / {@code checkPort}).
 * Pure-Java, no runtime dependencies, so the context is ignored.
 */
public final class NetworkProbeToolsProvider implements ToolProvider {
    @Override
    public Object create(ToolContext context) {
        return new NetworkProbeTools();
    }
}
