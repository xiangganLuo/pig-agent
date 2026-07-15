package io.pigagent.plugin.builtin;

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
 * End-to-end: the built-in plugins are discovered purely by their {@code META-INF/services}
 * declaration (classpath {@link ServiceLoaderPluginSource}) and, driven through the existing
 * {@link PluginRegistry} + a real {@link Toolkit}, register all of their {@code @Tool} tools — proving
 * "drop a class + a service line" wiring with no central assembly edit. Covers both the six pure
 * compute plugins and the three tools extracted from {@code pig-agent-tools} (web search / web fetch /
 * checklist), whose out-of-box discovery must survive the move.
 */
class BuiltinPluginDiscoveryTest {

    private static final Set<String> COMPUTE_TOOLS = Set.of(
            "currentDateTime", "convertTimezone", "epochToIso", "isoToEpoch",
            "generateUuid",
            "base64Encode", "base64Decode",
            "md5Hash", "sha256Hash",
            "jsonPrettyPrint", "jsonValidate",
            "randomNumber", "randomString");

    /** Extracted (non-core) tools: web search / web fetch / checklist. */
    private static final Set<String> EXTRACTED_TOOLS = Set.of(
            "webSearch",
            "fetchUrl",
            "createChecklist", "completeItem", "showChecklist");

    @Test
    void serviceLoaderDiscoversEveryBuiltinPlugin() {
        // Act
        List<Plugin> plugins = new ServiceLoaderPluginSource().discover();
        Set<String> ids = plugins.stream().map(Plugin::id).collect(Collectors.toSet());

        // Assert — all built-in plugins (compute + extracted) are found via the service file
        Set<String> catalogIds = PluginCatalog.all().stream().map(Plugin::id).collect(Collectors.toSet());
        assertThat(ids).containsAll(catalogIds);
        assertThat(catalogIds).contains("builtin:websearch", "builtin:webfetch", "builtin:checklist");
    }

    @Test
    void loadAndRegister_registersAllBuiltinTools() {
        // Arrange
        Toolkit toolkit = new Toolkit();

        // Act — classpath discovery → register into a real toolkit
        PluginRegistry.Result result = PluginRegistry.loadAndRegister(
                List.of(new ServiceLoaderPluginSource()), new ToolContext(null, null), toolkit);

        // Assert — every expected tool name landed in the toolkit (registration is independent of the
        // availability gate, so webSearch registers even without BRAVE_API_KEY).
        assertThat(result.failed).isEmpty();
        assertThat(result.toolsRegistered).containsAll(COMPUTE_TOOLS);
        assertThat(result.toolsRegistered).containsAll(EXTRACTED_TOOLS);
        assertThat(toolkit.getToolNames()).containsAll(COMPUTE_TOOLS);
        assertThat(toolkit.getToolNames()).containsAll(EXTRACTED_TOOLS);
    }
}
