package io.pigagent.plugin;

import io.agentscope.core.middleware.MiddlewareBase;
import io.pigagent.tool.spi.ToolContext;

import java.util.Collection;

/**
 * The registration surface handed to a {@link Plugin#register(PluginContext)} call. A plugin uses it
 * to contribute tools (instances with {@code @Tool} methods) and middlewares; the underlying
 * registration reuses the existing {@code Toolkit}/{@code ToolContext} and {@code AgentFactory}
 * middleware plumbing.
 *
 * <p><b>av2 Phase 5a:</b> the extension point is AgentScope 2.0 {@link MiddlewareBase} (the deprecated
 * 1.x {@code io.agentscope.core.hook.Hook} is no longer used anywhere in pig).
 *
 * <p>Fluent methods return {@code this} for chaining. Contributions are collected during
 * {@code register}; {@link PluginRegistry} reads them afterwards and applies the actual toolkit
 * registration + middleware wiring.
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

    /** Contribute one {@link MiddlewareBase}. Ignores {@code null}. */
    PluginContext addMiddleware(MiddlewareBase middleware);

    /** Contribute several middlewares. Ignores {@code null} collection / elements. */
    PluginContext addMiddlewares(Collection<? extends MiddlewareBase> middlewares);
}
