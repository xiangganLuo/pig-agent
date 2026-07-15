package io.pigagent.plugin;

import java.util.List;

/**
 * A source that discovers {@link Plugin} instances (classpath {@code ServiceLoader}, external jars in
 * a directory, …). Discovery MUST be fault-tolerant: an implementation should never throw — it
 * returns whatever it could load and swallows/logs the rest — so a broken source degrades to "no
 * plugins" instead of aborting startup.
 *
 * <p>Single abstract method, so tests can supply a source as a lambda.
 */
@FunctionalInterface
public interface PluginSource {

    /** Discover plugins from this source; returns empty (never {@code null}) when none/failure. */
    List<Plugin> discover();

    /** Human-readable name for logs; defaults to the implementation's simple class name. */
    default String name() {
        return getClass().getSimpleName();
    }
}
