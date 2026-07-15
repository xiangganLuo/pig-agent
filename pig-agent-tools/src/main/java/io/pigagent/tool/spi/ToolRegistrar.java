package io.pigagent.tool.spi;

import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.Toolkit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.ServiceLoader;
import java.util.Set;

/**
 * Auto-discovers builtin tools via {@link ServiceLoader} and registers them into a {@link Toolkit},
 * then applies any manual overrides. Design (change {@code tool-autoregister}):
 *
 * <ul>
 *   <li><b>Auto discovery (D1)</b> — {@link ToolProvider} implementations declared in
 *       {@code META-INF/services} are loaded and each yields one tool; a new tool is discovered by
 *       "dropping a file", not by editing the assembly wiring.</li>
 *   <li><b>Manual override with audit (D2)</b> — the manual overrides pass runs <em>after</em> auto
 *       discovery; a manual tool whose name collides with an auto-registered one replaces it and the
 *       override is logged at INFO. An <em>unexpected</em> same-name duplicate within a phase keeps
 *       the first and is logged at WARN — never silently stacked.</li>
 *   <li><b>Fail-safe (D3)</b> — a single provider/tool that fails to load, name-resolve, or register
 *       is caught, recorded, and skipped; the rest still register.</li>
 * </ul>
 *
 * <p>This registrar only decides <em>whether a tool enters the Toolkit</em>; permission veto,
 * availability gating, and return contracts are separate dimensions applied on top (D5).
 */
public final class ToolRegistrar {

    private static final Logger log = LoggerFactory.getLogger(ToolRegistrar.class);

    private ToolRegistrar() {
    }

    /** Outcome of a registration pass — for auditing and tests. */
    public static final class Result {
        /** Tool names successfully registered this pass. */
        public final Set<String> registered = new LinkedHashSet<>();
        /** Tool instances successfully registered this pass (for downstream availability gating). */
        public final List<Object> instances = new ArrayList<>();
        /** Tool names a manual tool overrode (removed an auto impl and replaced it). */
        public final Set<String> overrides = new LinkedHashSet<>();
        /** Tool names skipped because of an unexpected same-name duplicate (kept the first). */
        public final Set<String> duplicatesSkipped = new LinkedHashSet<>();
        /** Provider/tool class names that failed to load, resolve, or register. */
        public final List<String> failures = new ArrayList<>();
    }

    /**
     * Discover builtin tools via {@link ServiceLoader} and register them, then apply manual overrides.
     *
     * @param toolkit         the toolkit to register into
     * @param context         runtime dependencies for providers
     * @param manualOverrides tools registered after auto discovery; may override auto by name; may be
     *                        {@code null} or empty
     */
    public static Result registerAll(Toolkit toolkit, ToolContext context, List<Object> manualOverrides) {
        return register(toolkit, discoverProviders(), context, manualOverrides);
    }

    /**
     * Load {@link ToolProvider} implementations from {@code META-INF/services} (fail-safe: a bad
     * service line yields a {@link java.util.ServiceConfigurationError}, which is logged and skipped
     * without aborting discovery of the rest).
     */
    static List<ToolProvider> discoverProviders() {
        List<ToolProvider> providers = new ArrayList<>();
        Iterator<ToolProvider> it = ServiceLoader.load(ToolProvider.class).iterator();
        while (true) {
            try {
                if (!it.hasNext()) {
                    break;
                }
                providers.add(it.next());
            } catch (Throwable t) {
                log.warn("Tool provider failed to load, skipping: {}", t.toString());
            }
        }
        return providers;
    }

    /**
     * Register already-instantiated tools (e.g. contributed by a plugin) into {@code toolkit},
     * reusing the same de-dup/override plumbing as auto discovery: each tool registers with the
     * <em>auto</em> (non-override) semantics, so a name that already exists (a built-in registered
     * earlier, or an earlier tool in this list) is kept and the colliding tool is skipped
     * ({@link Result#duplicatesSkipped}), never silently stacked. A tool that fails to name-resolve
     * or register is isolated ({@link Result#failures}) and the rest still register (fail-safe).
     *
     * <p>Intended for composing external contributions (e.g. {@code pig-agent-plugin}) with built-in
     * auto-registration without duplicating the collision/fail-safe logic.
     *
     * @param toolkit the toolkit to register into (built-ins already registered → they win collisions)
     * @param tools   the tool instances to register; may be {@code null}/empty
     */
    public static Result registerTools(Toolkit toolkit, List<Object> tools) {
        Result result = new Result();
        if (tools != null) {
            for (Object tool : tools) {
                if (tool != null) {
                    registerOne(toolkit, tool, false, result);
                }
            }
        }
        return result;
    }

    /**
     * Register auto-discovered providers (phase 1) then manual overrides (phase 2). Package-private so
     * tests can drive it with an explicit provider list (no reliance on the services file).
     */
    static Result register(Toolkit toolkit, List<ToolProvider> providers, ToolContext context,
                           List<Object> manualOverrides) {
        Result result = new Result();
        for (ToolProvider provider : providers) {
            Object tool;
            try {
                tool = provider.create(context);
            } catch (Throwable t) {
                log.warn("Tool instantiation failed, skipping provider {}: {}",
                        provider.getClass().getName(), t.toString());
                result.failures.add(provider.getClass().getName());
                continue;
            }
            if (tool != null) {
                registerOne(toolkit, tool, false, result);
            }
        }
        if (manualOverrides != null) {
            for (Object tool : manualOverrides) {
                if (tool != null) {
                    registerOne(toolkit, tool, true, result);
                }
            }
        }
        return result;
    }

    private static void registerOne(Toolkit toolkit, Object tool, boolean manual, Result result) {
        Set<String> names;
        try {
            names = toolNamesOf(tool);
        } catch (Throwable t) {
            log.warn("Failed to resolve tool names, skipping {}: {}", tool.getClass().getName(), t.toString());
            result.failures.add(tool.getClass().getName());
            return;
        }
        if (names.isEmpty()) {
            log.warn("Class {} declares no @Tool methods, skipping", tool.getClass().getName());
            return;
        }
        Set<String> collisions = new LinkedHashSet<>();
        Set<String> existing = toolkit.getToolNames();
        for (String name : names) {
            if (existing.contains(name)) {
                collisions.add(name);
            }
        }
        if (collisions.isEmpty()) {
            applyRegistration(toolkit, tool, names, result);
            return;
        }
        if (manual) {
            for (String name : collisions) {
                toolkit.removeTool(name);
                log.info("Tool override: manual {} overrides same-named auto-registered tool ({})",
                        name, tool.getClass().getName());
                result.overrides.add(name);
            }
            applyRegistration(toolkit, tool, names, result);
        } else {
            log.warn("Duplicate tool name(s) {}, keeping the registered impl and skipping {} (not stacked)",
                    collisions, tool.getClass().getName());
            result.duplicatesSkipped.addAll(collisions);
        }
    }

    private static void applyRegistration(Toolkit toolkit, Object tool, Set<String> names, Result result) {
        try {
            toolkit.registration().tool(tool).apply();
            result.registered.addAll(names);
            result.instances.add(tool);
        } catch (Throwable t) {
            log.warn("Tool registration failed, skipping {}: {}", tool.getClass().getName(), t.toString());
            result.failures.add(tool.getClass().getName());
        }
    }

    /**
     * The tool names an instance contributes: each {@code @Tool} method's {@code name()}, or the
     * method name when {@code name()} is blank (matching AgentScope's own resolution).
     */
    static Set<String> toolNamesOf(Object tool) {
        Set<String> names = new LinkedHashSet<>();
        for (Method method : tool.getClass().getMethods()) {
            Tool annotation = method.getAnnotation(Tool.class);
            if (annotation != null) {
                String name = annotation.name();
                names.add(name == null || name.isBlank() ? method.getName() : name);
            }
        }
        return names;
    }
}
