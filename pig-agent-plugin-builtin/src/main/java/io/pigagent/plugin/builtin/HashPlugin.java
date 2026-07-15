package io.pigagent.plugin.builtin;

import io.pigagent.plugin.builtin.tool.HashTool;
import io.pigagent.tool.spi.ToolContext;

import java.util.List;

/** Plugin contributing {@link HashTool} (MD5 / SHA-256 digests). */
public final class HashPlugin extends AbstractToolPlugin {

    public HashPlugin() {
        super("builtin:hash");
    }

    @Override
    protected List<Object> createTools(ToolContext context) {
        return List.of(new HashTool());
    }
}
