package io.pigagent.plugin.builtin;

import io.pigagent.plugin.Plugin;

import java.util.List;

/**
 * Registry / factory that assembles the built-in plugin set as a single source of truth. Used for
 * programmatic introspection and tests; the runtime discovery path is the classpath
 * {@code META-INF/services/io.pigagent.plugin.Plugin} declaration, driven by
 * {@code ServiceLoaderPluginSource}.
 *
 * <p>The service-declaration file and {@link #all()} are deliberately two views of the same set (one
 * for {@link java.util.ServiceLoader} discovery, one for programmatic use). A consistency test asserts
 * their plugin-id sets are equal, so adding a plugin to one without the other is caught as drift.
 *
 * <p>Two groups: six pure-compute plugins (time/uuid/base64/hash/json/random) and three non-core tools
 * extracted from {@code pig-agent-tools} (web search / web fetch / checklist) — the latter keep their
 * availability gate, SSRF guard, and risk classification after the move.
 */
public final class PluginCatalog {

    private PluginCatalog() {
    }

    /** Every plugin shipped by this built-in module, in a stable order. */
    public static List<Plugin> all() {
        return List.of(
                new TimePlugin(),
                new UuidPlugin(),
                new Base64Plugin(),
                new HashPlugin(),
                new JsonPlugin(),
                new RandomPlugin(),
                new WebSearchPlugin(),
                new WebFetchPlugin(),
                new ChecklistPlugin());
    }
}
