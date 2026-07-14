package io.pigagent.plugin;

import io.agentscope.core.hook.Hook;
import io.pigagent.tool.spi.ToolContext;

import java.util.Collection;

/**
 * The registration surface handed to a {@link Plugin#register(PluginContext)} call. A plugin uses it
 * to contribute tools (instances with {@code @Tool} methods) and hooks; the underlying registration
 * reuses the existing {@code Toolkit}/{@code ToolContext} and {@code AgentFactory} hook plumbing.
 *
 * <p>Fluent methods return {@code this} for chaining. Contributions are collected during
 * {@code register}; {@link PluginRegistry} reads them afterwards and applies the actual toolkit
 * registration + hook wiring.
 */
public interface PluginContext {

    /**
     * Runtime dependencies a plugin may need to build its tools (task manager, skills dir, workspace
     * root, web allowlist) — the same {@link ToolContext} the built-in tools receive. Never
     * {@code null}.
     */
    ToolContext toolContext();

    /** Contribute one tool instance (a class with {@code @Tool} methods). Ignores {@code null}. */
    PluginContext addTool(Object tool);

    /** Contribute several tool instances. Ignores {@code null} collection / elements. */
    PluginContext addTools(Collection<?> tools);

    /** Contribute one {@link Hook}. Ignores {@code null}. */
    PluginContext addHook(Hook hook);

    /** Contribute several hooks. Ignores {@code null} collection / elements. */
    PluginContext addHooks(Collection<? extends Hook> hooks);
}
