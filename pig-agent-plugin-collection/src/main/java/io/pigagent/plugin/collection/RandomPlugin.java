package io.pigagent.plugin.collection;

import io.pigagent.plugin.collection.tool.RandomTool;
import io.pigagent.tool.spi.ToolContext;

import java.util.List;

/** Plugin contributing {@link RandomTool} (random number / string). */
public final class RandomPlugin extends AbstractToolPlugin {

    public RandomPlugin() {
        super("collection:random");
    }

    @Override
    protected List<Object> createTools(ToolContext context) {
        return List.of(new RandomTool());
    }
}
