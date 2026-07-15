package io.pigagent.plugin.builtin;

import io.pigagent.plugin.builtin.tool.RandomTool;
import io.pigagent.tool.spi.ToolContext;

import java.util.List;

/** Plugin contributing {@link RandomTool} (random number / string). */
public final class RandomPlugin extends AbstractToolPlugin {

    public RandomPlugin() {
        super("builtin:random");
    }

    @Override
    protected List<Object> createTools(ToolContext context) {
        return List.of(new RandomTool());
    }
}
