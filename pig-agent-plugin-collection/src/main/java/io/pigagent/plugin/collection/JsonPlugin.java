package io.pigagent.plugin.collection;

import io.pigagent.plugin.collection.tool.JsonTool;
import io.pigagent.tool.spi.ToolContext;

import java.util.List;

/** Plugin contributing {@link JsonTool} (JSON pretty-print / validate). */
public final class JsonPlugin extends AbstractToolPlugin {

    public JsonPlugin() {
        super("collection:json");
    }

    @Override
    protected List<Object> createTools(ToolContext context) {
        return List.of(new JsonTool());
    }
}
