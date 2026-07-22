package io.pigagent.tool.spi.providers;

import io.pigagent.tool.os.EnvironmentTools;
import io.pigagent.tool.os.NetworkProbeTools;
import io.pigagent.tool.os.ProcessTools;
import io.pigagent.tool.os.SystemInfoTools;
import io.pigagent.tool.spi.ToolContext;
import io.pigagent.tool.spi.ToolProvider;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.ServiceLoader;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Wiring test for the OS-tool providers: each builds its pure-Java tool with no context dependency
 * (even when the context is {@code null}), and all four are discovered via {@code META-INF/services}
 * so {@code ToolRegistrar} picks them up with no assembly edit.
 */
class OsToolsProviderTest {

    @Test
    void providersBuildWithoutContext() {
        assertThat(new SystemInfoToolsProvider().create(null)).isInstanceOf(SystemInfoTools.class);
        assertThat(new ProcessToolsProvider().create(null)).isInstanceOf(ProcessTools.class);
        assertThat(new EnvironmentToolsProvider().create(null)).isInstanceOf(EnvironmentTools.class);
        assertThat(new NetworkProbeToolsProvider().create(null)).isInstanceOf(NetworkProbeTools.class);
        // context is ignored but must be accepted
        ToolContext ctx = new ToolContext(null, null);
        assertThat(new SystemInfoToolsProvider().create(ctx)).isInstanceOf(SystemInfoTools.class);
    }

    @Test
    void providersDiscoveredViaServiceLoader() {
        List<ToolProvider> providers = ServiceLoader.load(ToolProvider.class).stream()
                .map(ServiceLoader.Provider::get).toList();

        assertThat(providers).anyMatch(p -> p instanceof SystemInfoToolsProvider);
        assertThat(providers).anyMatch(p -> p instanceof ProcessToolsProvider);
        assertThat(providers).anyMatch(p -> p instanceof EnvironmentToolsProvider);
        assertThat(providers).anyMatch(p -> p instanceof NetworkProbeToolsProvider);
    }
}
