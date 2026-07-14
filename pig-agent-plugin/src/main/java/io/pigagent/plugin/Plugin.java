package io.pigagent.plugin;

/**
 * SPI for an external extension bundle (borrowing Hermes' plugin model). A plugin contributes tools
 * and/or hooks through a single {@link #register(PluginContext)} entrypoint, composing the existing
 * tool ({@code io.pigagent.tool.spi.ToolProvider}) and hook ({@code io.agentscope.core.hook.Hook})
 * plumbing rather than replacing it.
 *
 * <p>Implementations are discovered by a {@link PluginSource} — declared in
 * {@code META-INF/services/io.pigagent.plugin.Plugin} on the classpath, or packaged in a jar under
 * the workspace {@code plugins/} directory — and driven by {@link PluginRegistry}. A plugin that
 * throws from {@link #register(PluginContext)} is isolated and skipped (fail-safe), so one bad plugin
 * never breaks startup or the other plugins.
 */
public interface Plugin {

    /**
     * Stable identifier for logging and cross-source de-duplication. Defaults to the fully-qualified
     * class name; override only if a plugin needs a curated id.
     */
    default String id() {
        return getClass().getName();
    }

    /**
     * Contribute tools/hooks via the context. Called once at startup. Any exception thrown here is
     * caught by {@link PluginRegistry}, logged, and the plugin's partial contributions are discarded.
     */
    void register(PluginContext ctx);
}
