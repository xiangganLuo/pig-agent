package io.pigagent.tool.spi.providers;

import io.pigagent.tool.permission.PermissionDeniedTool;
import io.pigagent.tool.spi.ToolContext;
import io.pigagent.tool.spi.ToolProvider;

/** Auto-discovery provider for the permission-deny sentinel {@link PermissionDeniedTool}. */
public final class PermissionDeniedToolProvider implements ToolProvider {
    @Override
    public Object create(ToolContext context) {
        return new PermissionDeniedTool();
    }
}
