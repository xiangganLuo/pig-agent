package io.pigagent.tool.spi.providers;

import io.pigagent.tool.loop.LoopDetectedTool;
import io.pigagent.tool.spi.ToolContext;
import io.pigagent.tool.spi.ToolProvider;

/** Auto-discovery provider for the loop-detection stop sentinel {@link LoopDetectedTool}. */
public final class LoopDetectedToolProvider implements ToolProvider {
    @Override
    public Object create(ToolContext context) {
        return new LoopDetectedTool();
    }
}
