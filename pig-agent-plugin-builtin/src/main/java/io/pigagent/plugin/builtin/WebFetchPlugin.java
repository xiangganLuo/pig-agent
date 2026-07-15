package io.pigagent.plugin.builtin;

import io.pigagent.plugin.builtin.tool.SmartWebFetchTool;
import io.pigagent.tool.spi.ToolContext;

import java.util.List;

/**
 * Plugin contributing {@link SmartWebFetchTool} (the {@code fetchUrl} tool). Extracted from the core
 * tools module because it performs network egress. The optional {@code tools.web.allowed-hosts}
 * allowlist is injected from the shared {@link ToolContext} (this is exactly what
 * {@code PluginContext.toolContext()} is for), so the SSRF guard + host-allowlist behavior is
 * preserved verbatim after the move.
 */
public final class WebFetchPlugin extends AbstractToolPlugin {

    public WebFetchPlugin() {
        super("builtin:webfetch");
    }

    @Override
    protected List<Object> createTools(ToolContext context) {
        List<String> allowedHosts = context == null ? List.of() : context.webAllowedHosts();
        return List.of(new SmartWebFetchTool(allowedHosts));
    }
}
