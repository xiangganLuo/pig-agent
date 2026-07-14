package io.pigagent.plugin;

import io.agentscope.core.tool.Toolkit;
import io.pigagent.tool.spi.ToolContext;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link ServiceLoaderPluginSource}: a plugin declared only in the test-scoped
 * {@code META-INF/services/io.pigagent.plugin.Plugin} file is discovered purely by "declaring a
 * service line" and, routed through {@link PluginRegistry}, contributes its tool into the toolkit —
 * proving the end-to-end classpath discovery → register path.
 */
class ServiceLoaderPluginSourceTest {

    @Test
    void discoversPluginDeclaredInServicesFile() {
        // Arrange
        ServiceLoaderPluginSource source = new ServiceLoaderPluginSource();

        // Act
        List<Plugin> plugins = source.discover();

        // Assert
        assertThat(plugins).anyMatch(p -> p instanceof ClasspathSamplePlugin);
    }

    @Test
    void classpathPlugin_registersItsToolEndToEnd() {
        // Arrange
        Toolkit toolkit = new Toolkit();

        // Act — full path: classpath ServiceLoader discovery → register → toolkit
        PluginRegistry.Result result = PluginRegistry.loadAndRegister(
                List.of(new ServiceLoaderPluginSource()), new ToolContext(null, null), toolkit);

        // Assert
        assertThat(result.toolsRegistered).contains("classpathPluginTool");
        assertThat(toolkit.getToolNames()).contains("classpathPluginTool");
    }
}
