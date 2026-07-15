package io.pigagent.tool.spi.providers;

import io.pigagent.tool.sandbox.SandboxPolicy;
import io.pigagent.tool.shell.ShellTools;
import io.pigagent.tool.spi.ToolContext;
import io.pigagent.tool.spi.ToolProvider;

/**
 * Auto-discovery provider for {@link ShellTools}. When a {@link SandboxPolicy} is present on the
 * context, the shell tool is built under it; otherwise it uses the conservative built-in default.
 */
public final class ShellToolsProvider implements ToolProvider {
    @Override
    public Object create(ToolContext context) {
        SandboxPolicy policy = context == null ? null : context.sandboxPolicy();
        return policy == null ? new ShellTools() : new ShellTools(policy);
    }
}
