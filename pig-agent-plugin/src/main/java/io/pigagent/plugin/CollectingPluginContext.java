package io.pigagent.plugin;

import io.agentscope.core.middleware.MiddlewareBase;
import io.pigagent.tool.spi.ToolContext;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/**
 * A {@link PluginContext} that accumulates a single plugin's contributions into in-memory lists.
 * {@link PluginRegistry} creates one per plugin (so a throwing plugin's partial contributions can be
 * discarded independently) and reads {@link #tools()} / {@link #middlewares()} back after a successful
 * {@code register}.
 */
public final class CollectingPluginContext implements PluginContext {

    private final ToolContext toolContext;
    private final List<Object> tools = new ArrayList<>();
    private final List<MiddlewareBase> middlewares = new ArrayList<>();

    public CollectingPluginContext(ToolContext toolContext) {
        this.toolContext = toolContext;
    }

    @Override
    public ToolContext toolContext() {
        return toolContext;
    }

    @Override
    public PluginContext addTool(Object tool) {
        if (tool != null) {
            tools.add(tool);
        }
        return this;
    }

    @Override
    public PluginContext addTools(Collection<?> toolsToAdd) {
        if (toolsToAdd != null) {
            for (Object tool : toolsToAdd) {
                addTool(tool);
            }
        }
        return this;
    }

    @Override
    public PluginContext addMiddleware(MiddlewareBase middleware) {
        if (middleware != null) {
            middlewares.add(middleware);
        }
        return this;
    }

    @Override
    public PluginContext addMiddlewares(Collection<? extends MiddlewareBase> middlewaresToAdd) {
        if (middlewaresToAdd != null) {
            for (MiddlewareBase middleware : middlewaresToAdd) {
                addMiddleware(middleware);
            }
        }
        return this;
    }

    /** The tools contributed so far (live order preserved). */
    public List<Object> tools() {
        return tools;
    }

    /** The middlewares contributed so far (live order preserved). */
    public List<MiddlewareBase> middlewares() {
        return middlewares;
    }
}
