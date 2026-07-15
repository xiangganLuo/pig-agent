package io.pigagent.plugin.collection;

import io.pigagent.plugin.Plugin;

import java.util.List;

/**
 * Registry / factory that assembles the collection's plugin set as a single source of truth. Used for
 * programmatic introspection and tests; the runtime discovery path is the classpath
 * {@code META-INF/services/io.pigagent.plugin.Plugin} declaration, driven by
 * {@code ServiceLoaderPluginSource}.
 *
 * <p>The service-declaration file and {@link #all()} are deliberately two views of the same set (one
 * for {@link java.util.ServiceLoader} discovery, one for programmatic use). A consistency test asserts
 * their plugin-id sets are equal, so adding a plugin to one without the other is caught as drift.
 */
public final class PluginCatalog {

    private PluginCatalog() {
    }

    /** Every plugin shipped by this collection, in a stable order. */
    public static List<Plugin> all() {
        return List.of(
                new TimePlugin(),
                new UuidPlugin(),
                new Base64Plugin(),
                new HashPlugin(),
                new JsonPlugin(),
                new RandomPlugin());
    }
}
