package io.pigagent.plugin.builtin;

import io.pigagent.plugin.builtin.tool.BraveWebSearchTool;
import io.pigagent.tool.spi.ToolContext;

import java.util.List;

/**
 * Plugin contributing {@link BraveWebSearchTool} (the {@code webSearch} tool). Extracted from the core
 * tools module because it needs an external {@code BRAVE_API_KEY} — the tool declares itself
 * unavailable (and is hidden from the model schema) when the key is unset, via its {@code
 * ToolAvailability} implementation, which the availability gate still evaluates for plugin tools.
 */
public final class WebSearchPlugin extends AbstractToolPlugin {

    public WebSearchPlugin() {
        super("builtin:websearch");
    }

    @Override
    protected List<Object> createTools(ToolContext context) {
        return List.of(new BraveWebSearchTool());
    }
}
