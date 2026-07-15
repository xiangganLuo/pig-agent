package io.pigagent.plugin.collection;

import io.pigagent.plugin.collection.tool.Base64Tool;
import io.pigagent.tool.spi.ToolContext;

import java.util.List;

/** Plugin contributing {@link Base64Tool} (Base64 encode/decode). */
public final class Base64Plugin extends AbstractToolPlugin {

    public Base64Plugin() {
        super("collection:base64");
    }

    @Override
    protected List<Object> createTools(ToolContext context) {
        return List.of(new Base64Tool());
    }
}
