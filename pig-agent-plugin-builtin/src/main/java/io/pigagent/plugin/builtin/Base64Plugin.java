package io.pigagent.plugin.builtin;

import io.pigagent.plugin.builtin.tool.Base64Tool;
import io.pigagent.tool.spi.ToolContext;

import java.util.List;

/** Plugin contributing {@link Base64Tool} (Base64 encode/decode). */
public final class Base64Plugin extends AbstractToolPlugin {

    public Base64Plugin() {
        super("builtin:base64");
    }

    @Override
    protected List<Object> createTools(ToolContext context) {
        return List.of(new Base64Tool());
    }
}
