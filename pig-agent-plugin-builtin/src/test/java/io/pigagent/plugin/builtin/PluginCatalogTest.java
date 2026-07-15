package io.pigagent.plugin.builtin;

import io.pigagent.plugin.Plugin;
import org.junit.jupiter.api.Test;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The registry/factory {@link PluginCatalog} is the single source of truth for the plugin set, and the
 * {@code META-INF/services} declaration (used by {@code ServiceLoader} at runtime) MUST list exactly
 * the same classes — a drift guard so adding a plugin to one place without the other fails the build.
 */
class PluginCatalogTest {

    private static final String SERVICES = "META-INF/services/io.pigagent.plugin.Plugin";

    @Test
    void all_isNonEmptyWithUniqueIds() {
        List<Plugin> plugins = PluginCatalog.all();
        Set<String> ids = plugins.stream().map(Plugin::id).collect(Collectors.toSet());

        assertThat(plugins).isNotEmpty();
        assertThat(ids).hasSameSizeAs(plugins);
        assertThat(ids).allMatch(id -> id.startsWith("builtin:"));
    }

    @Test
    void catalogClassesMatchServicesFileDeclaration() throws Exception {
        // Arrange — the FQCNs the catalog assembles programmatically
        Set<String> catalogClasses = PluginCatalog.all().stream()
                .map(p -> p.getClass().getName())
                .collect(Collectors.toSet());

        // Act — the FQCNs declared in the runtime service file
        Set<String> declaredClasses = readServiceLines();

        // Assert — the two views of the plugin set are identical (no drift)
        assertThat(declaredClasses).isEqualTo(catalogClasses);
    }

    private static Set<String> readServiceLines() throws Exception {
        try (InputStream in = Thread.currentThread().getContextClassLoader().getResourceAsStream(SERVICES);
             BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
            return reader.lines()
                    .map(String::trim)
                    .filter(line -> !line.isEmpty() && !line.startsWith("#"))
                    .collect(Collectors.toSet());
        }
    }
}
