package io.pigagent.tool.spi.providers;

import static org.assertj.core.api.Assertions.assertThat;

import io.pigagent.tool.sandbox.SandboxPolicy;
import io.pigagent.tool.shell.ShellTools;
import io.pigagent.tool.spi.ToolContext;
import org.junit.jupiter.api.Test;

/**
 * Wiring test: the provider builds a {@link ShellTools} whether or not a sandbox policy is present on
 * the context (backward compatible), so the exec-sandbox is injected through the SPI path.
 */
class ShellToolsProviderTest {

    private final ShellToolsProvider provider = new ShellToolsProvider();

    @Test
    void buildsShellToolsWithoutSandboxPolicy() {
        ToolContext ctx = new ToolContext(null, null);
        assertThat(ctx.sandboxPolicy()).isNull();
        assertThat(provider.create(ctx)).isInstanceOf(ShellTools.class);
    }

    @Test
    void buildsShellToolsWithSandboxPolicy() {
        SandboxPolicy policy = SandboxPolicy.defaults().withTimeoutSeconds(5);
        ToolContext ctx = new ToolContext(null, null, null, null, policy);
        assertThat(ctx.sandboxPolicy()).isSameAs(policy);
        assertThat(provider.create(ctx)).isInstanceOf(ShellTools.class);
    }

    @Test
    void buildsShellToolsWhenContextIsNull() {
        assertThat(provider.create(null)).isInstanceOf(ShellTools.class);
    }
}
