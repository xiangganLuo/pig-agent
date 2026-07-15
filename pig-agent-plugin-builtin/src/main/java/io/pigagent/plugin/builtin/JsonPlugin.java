package io.pigagent.plugin.builtin;

import io.pigagent.plugin.builtin.tool.JsonTool;
import io.pigagent.tool.spi.ToolContext;

import java.util.List;

/** Plugin contributing {@link JsonTool} (JSON pretty-print / validate). */
public final class JsonPlugin extends AbstractToolPlugin {

    public JsonPlugin() {
        super("builtin:json");
    }

    @Override
    protected List<Object> createTools(ToolContext context) {
        return List.of(new JsonTool());
    }
}
