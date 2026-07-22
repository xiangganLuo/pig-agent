package io.pigagent.tool.spi.providers;

import io.pigagent.tool.os.EnvironmentTools;
import io.pigagent.tool.spi.ToolContext;
import io.pigagent.tool.spi.ToolProvider;

/**
 * Auto-discovery provider for {@link EnvironmentTools} ({@code getEnvironment} / {@code whichCommand}).
 * Uses the real process environment; no runtime dependencies, so the context is ignored.
 */
public final class EnvironmentToolsProvider implements ToolProvider {
    @Override
    public Object create(ToolContext context) {
        return new EnvironmentTools();
    }
}
