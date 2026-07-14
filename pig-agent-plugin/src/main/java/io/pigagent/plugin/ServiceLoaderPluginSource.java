package io.pigagent.plugin;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.ServiceLoader;

/**
 * Discovers plugins declared in {@code META-INF/services/io.pigagent.plugin.Plugin} on a classpath,
 * via {@link ServiceLoader}. Fault-tolerant: a single bad service line yields a
 * {@link java.util.ServiceConfigurationError}, which is logged and skipped without aborting discovery
 * of the rest (mirrors {@code ToolRegistrar.discoverProviders}).
 */
public final class ServiceLoaderPluginSource implements PluginSource {

    private static final Logger log = LoggerFactory.getLogger(ServiceLoaderPluginSource.class);

    private final ClassLoader classLoader;

    /** Use the current thread's context classloader (falling back to this class's loader). */
    public ServiceLoaderPluginSource() {
        this(Thread.currentThread().getContextClassLoader());
    }

    public ServiceLoaderPluginSource(ClassLoader classLoader) {
        this.classLoader = classLoader != null ? classLoader : ServiceLoaderPluginSource.class.getClassLoader();
    }

    @Override
    public String name() {
        return "classpath";
    }

    @Override
    public List<Plugin> discover() {
        List<Plugin> plugins = new ArrayList<>();
        Iterator<Plugin> it = ServiceLoader.load(Plugin.class, classLoader).iterator();
        while (true) {
            try {
                if (!it.hasNext()) {
                    break;
                }
                plugins.add(it.next());
            } catch (Throwable t) {
                log.warn("Plugin failed to load from classpath, skipping: {}", t.toString());
            }
        }
        return plugins;
    }
}
