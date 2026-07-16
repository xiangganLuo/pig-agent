package io.pigagent.plugin;

import io.agentscope.core.middleware.MiddlewareBase;
import io.agentscope.core.tool.Toolkit;
import io.pigagent.tool.spi.ToolContext;
import io.pigagent.tool.spi.ToolRegistrar;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Loads plugins from a list of {@link PluginSource}s and applies their contributions, composing the
 * existing tool auto-registration ({@link ToolRegistrar}) rather than replacing it.
 *
 * <p>Flow ({@code plugin-system} design D3/D4):
 * <ol>
 *   <li><b>Discover</b> across all sources in order; a failing source is skipped; plugins are
 *       de-duplicated by {@link Plugin#id()} (first-wins, WARN on a duplicate).</li>
 *   <li><b>Register per plugin</b> into its own {@link CollectingPluginContext}; a plugin whose
 *       {@code register} throws is isolated and its partial contributions discarded (fail-safe).</li>
 *   <li><b>Register tools</b> via {@link ToolRegistrar#registerTools} — same de-dup/override rules as
 *       auto discovery: built-ins are registered first so they win any name collision (first-wins);
 *       a colliding plugin tool is skipped and recorded, never silently stacked.</li>
 *   <li><b>Collect middlewares</b> in order for the caller to append to the agent's middleware list
 *       (av2 Phase 5a — 2.0 {@link MiddlewareBase}, replacing the 1.x hook contribution).</li>
 * </ol>
 *
 * <p>This registrar only decides how plugin-contributed extensions enter the runtime; the permission
 * veto, availability gating, and return contracts apply on top unchanged.
 */
public final class PluginRegistry {

    private static final Logger log = LoggerFactory.getLogger(PluginRegistry.class);

    private PluginRegistry() {
    }

    /** Outcome of a plugin load+register pass — for auditing and tests. */
    public static final class Result {
        /** Total plugins discovered (after cross-source de-duplication). */
        public int discovered;
        /** Ids of plugins whose {@code register} completed successfully. */
        public final List<String> loaded = new ArrayList<>();
        /** Ids of plugins whose {@code register} threw (isolated, contributions discarded). */
        public final List<String> failed = new ArrayList<>();
        /** Tool names successfully registered into the toolkit by plugins. */
        public final Set<String> toolsRegistered = new LinkedHashSet<>();
        /** Tool names skipped because of a name collision (built-in or earlier plugin). */
        public final Set<String> toolsSkipped = new LinkedHashSet<>();
        /** Tool instances successfully registered (for downstream availability gating). */
        public final List<Object> toolInstances = new ArrayList<>();
        /** Middlewares contributed by loaded plugins, in discovery order (av2 Phase 5a). */
        public final List<MiddlewareBase> middlewares = new ArrayList<>();
    }

    /**
     * Discover plugins from {@code sources}, run their {@code register}, and register contributed
     * tools into {@code toolkit}. Contributed middlewares are returned on the {@link Result} for the
     * caller to wire into the agent.
     *
     * @param sources     discovery sources, tried in order; may be {@code null}/empty (→ no plugins)
     * @param toolContext runtime dependencies handed to each plugin (must not be {@code null})
     * @param toolkit     the toolkit plugin tools are registered into (built-ins already registered)
     */
    public static Result loadAndRegister(List<PluginSource> sources, ToolContext toolContext, Toolkit toolkit) {
        Result result = new Result();
        List<Plugin> plugins = discover(sources);
        result.discovered = plugins.size();

        List<Object> contributedTools = new ArrayList<>();
        for (Plugin plugin : plugins) {
            String id = safeId(plugin);
            CollectingPluginContext ctx = new CollectingPluginContext(toolContext);
            try {
                plugin.register(ctx);
            } catch (Throwable t) {
                log.warn("Plugin '{}' failed to register, skipping (fail-safe): {}", id, t.toString());
                result.failed.add(id);
                continue; // discard this plugin's partial contributions
            }
            contributedTools.addAll(ctx.tools());
            result.middlewares.addAll(ctx.middlewares());
            result.loaded.add(id);
            log.info("Plugin registered: {} (+{} tool(s), +{} middleware(s))",
                    id, ctx.tools().size(), ctx.middlewares().size());
        }

        ToolRegistrar.Result toolReg = ToolRegistrar.registerTools(toolkit, contributedTools);
        result.toolsRegistered.addAll(toolReg.registered);
        result.toolsSkipped.addAll(toolReg.duplicatesSkipped);
        result.toolInstances.addAll(toolReg.instances);
        if (!result.loaded.isEmpty() || !result.failed.isEmpty()) {
            log.info("Plugins loaded: {} (failed: {}); plugin tools: {}",
                    result.loaded, result.failed, result.toolsRegistered);
        }
        return result;
    }

    /** Discover across all sources in order, de-duplicating by plugin id (first-wins). */
    static List<Plugin> discover(List<PluginSource> sources) {
        List<Plugin> out = new ArrayList<>();
        if (sources == null) {
            return out;
        }
        Set<String> seenIds = new LinkedHashSet<>();
        for (PluginSource source : sources) {
            if (source == null) {
                continue;
            }
            List<Plugin> found;
            try {
                found = source.discover();
            } catch (Throwable t) {
                log.warn("Plugin source '{}' failed, skipping: {}", sourceName(source), t.toString());
                continue;
            }
            if (found == null) {
                continue;
            }
            for (Plugin plugin : found) {
                if (plugin == null) {
                    continue;
                }
                String id = safeId(plugin);
                if (!seenIds.add(id)) {
                    log.warn("Duplicate plugin id '{}' from source '{}', keeping first",
                            id, sourceName(source));
                    continue;
                }
                out.add(plugin);
            }
        }
        return out;
    }

    private static String safeId(Plugin plugin) {
        try {
            String id = plugin.id();
            return id == null || id.isBlank() ? plugin.getClass().getName() : id;
        } catch (Throwable t) {
            return plugin.getClass().getName();
        }
    }

    private static String sourceName(PluginSource source) {
        try {
            return source.name();
        } catch (Throwable t) {
            return source.getClass().getSimpleName();
        }
    }
}
