package io.pigagent.plugin;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.ServiceLoader;
import java.util.stream.Stream;

/**
 * Discovers plugins packaged as jars under an external directory (default {@code workspace/plugins/}).
 * It builds a child {@link URLClassLoader} over the {@code *.jar} files found there and runs
 * {@link ServiceLoader} against it, so external plugins need only ship a
 * {@code META-INF/services/io.pigagent.plugin.Plugin} entry.
 *
 * <p>Fault-tolerant by design: a {@code null} / missing / non-directory path, an empty directory, or
 * a directory with no jars all return an empty list without throwing; a jar that fails to yield a
 * valid plugin is logged and skipped. External jars are arbitrary code and are NOT sandboxed — only
 * place trusted jars here (see the {@code plugin-system} design, R2).
 */
public final class DirectoryPluginSource implements PluginSource {

    private static final Logger log = LoggerFactory.getLogger(DirectoryPluginSource.class);
    private static final String JAR_SUFFIX = ".jar";

    private final Path directory;

    public DirectoryPluginSource(Path directory) {
        this.directory = directory;
    }

    @Override
    public String name() {
        return "directory:" + directory;
    }

    @Override
    public List<Plugin> discover() {
        if (directory == null || !Files.isDirectory(directory)) {
            return List.of();
        }
        List<URL> jarUrls = scanJarUrls();
        if (jarUrls.isEmpty()) {
            return List.of();
        }
        return loadFromJars(jarUrls);
    }

    /** Collect the {@code file:} URLs of every {@code *.jar} directly under the directory. */
    private List<URL> scanJarUrls() {
        List<URL> urls = new ArrayList<>();
        try (Stream<Path> entries = Files.list(directory)) {
            entries.filter(DirectoryPluginSource::isJar).forEach(jar -> {
                try {
                    urls.add(jar.toUri().toURL());
                } catch (Exception e) {
                    log.warn("Skipping unreadable plugin jar {}: {}", jar, e.toString());
                }
            });
        } catch (IOException e) {
            log.warn("Failed to scan plugin directory {}: {}", directory, e.toString());
        }
        return urls;
    }

    /**
     * Build a child classloader over the jars and ServiceLoader-discover plugins, fault-tolerant.
     *
     * <p>The {@link URLClassLoader} is deliberately <em>not</em> closed: plugin classes (tools/hooks)
     * are loaded lazily and must stay resolvable for the application's lifetime (this spec does no
     * hot-reload — see the {@code plugin-system} Non-Goals). The loader is kept alive implicitly by
     * the returned {@link Plugin} instances (and the tools they register), and is reclaimed by GC when
     * those become unreachable. On Windows this keeps the jar files locked while the app runs, which
     * is the normal trade-off for a classpath-style plugin loader.
     */
    private List<Plugin> loadFromJars(List<URL> jarUrls) {
        List<Plugin> plugins = new ArrayList<>();
        URLClassLoader loader = new URLClassLoader(
                jarUrls.toArray(new URL[0]), getClass().getClassLoader());
        Iterator<Plugin> it = ServiceLoader.load(Plugin.class, loader).iterator();
        while (true) {
            try {
                if (!it.hasNext()) {
                    break;
                }
                plugins.add(it.next());
            } catch (Throwable t) {
                log.warn("Plugin failed to load from {}, skipping: {}", name(), t.toString());
            }
        }
        return plugins;
    }

    private static boolean isJar(Path path) {
        return Files.isRegularFile(path)
                && path.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(JAR_SUFFIX);
    }
}
