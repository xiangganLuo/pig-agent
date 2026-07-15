package io.pigagent.plugin;

import io.agentscope.core.hook.Hook;
import io.pigagent.tool.spi.ToolContext;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/**
 * A {@link PluginContext} that accumulates a single plugin's contributions into in-memory lists.
 * {@link PluginRegistry} creates one per plugin (so a throwing plugin's partial contributions can be
 * discarded independently) and reads {@link #tools()} / {@link #hooks()} back after a successful
 * {@code register}.
 */
public final class CollectingPluginContext implements PluginContext {

    private final ToolContext toolContext;
    private final List<Object> tools = new ArrayList<>();
    private final List<Hook> hooks = new ArrayList<>();

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
    public PluginContext addHook(Hook hook) {
        if (hook != null) {
            hooks.add(hook);
        }
        return this;
    }

    @Override
    public PluginContext addHooks(Collection<? extends Hook> hooksToAdd) {
        if (hooksToAdd != null) {
            for (Hook hook : hooksToAdd) {
                addHook(hook);
            }
        }
        return this;
    }

    /** The tools contributed so far (live order preserved). */
    public List<Object> tools() {
        return tools;
    }

    /** The hooks contributed so far (live order preserved). */
    public List<Hook> hooks() {
        return hooks;
    }
}
