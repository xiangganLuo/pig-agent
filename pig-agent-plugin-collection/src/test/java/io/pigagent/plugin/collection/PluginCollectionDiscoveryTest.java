package io.pigagent.plugin.collection;

import io.agentscope.core.tool.Toolkit;
import io.pigagent.plugin.Plugin;
import io.pigagent.plugin.PluginRegistry;
import io.pigagent.plugin.ServiceLoaderPluginSource;
import io.pigagent.tool.spi.ToolContext;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * End-to-end: the collection's plugins are discovered purely by their {@code META-INF/services}
 * declaration (classpath {@link ServiceLoaderPluginSource}) and, driven through the existing
 * {@link PluginRegistry} + a real {@link Toolkit}, register all of their {@code @Tool} tools — proving
 * "drop a class + a service line" wiring with no central assembly edit.
 */
class PluginCollectionDiscoveryTest {

    private static final Set<String> EXPECTED_TOOLS = Set.of(
            "currentDateTime", "convertTimezone", "epochToIso", "isoToEpoch",
            "generateUuid",
            "base64Encode", "base64Decode",
            "md5Hash", "sha256Hash",
            "jsonPrettyPrint", "jsonValidate",
            "randomNumber", "randomString");

    @Test
    void serviceLoaderDiscoversEveryCollectionPlugin() {
        // Act
        List<Plugin> plugins = new ServiceLoaderPluginSource().discover();
        Set<String> ids = plugins.stream().map(Plugin::id).collect(Collectors.toSet());

        // Assert — all six collection plugins are found via the service file
        Set<String> catalogIds = PluginCatalog.all().stream().map(Plugin::id).collect(Collectors.toSet());
        assertThat(ids).containsAll(catalogIds);
    }

    @Test
    void loadAndRegister_registersAllCollectionTools() {
        // Arrange
        Toolkit toolkit = new Toolkit();

        // Act — classpath discovery → register into a real toolkit
        PluginRegistry.Result result = PluginRegistry.loadAndRegister(
                List.of(new ServiceLoaderPluginSource()), new ToolContext(null, null), toolkit);

        // Assert — every expected tool name landed in the toolkit
        assertThat(result.failed).isEmpty();
        assertThat(result.toolsRegistered).containsAll(EXPECTED_TOOLS);
        assertThat(toolkit.getToolNames()).containsAll(EXPECTED_TOOLS);
    }
}
