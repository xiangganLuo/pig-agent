package io.pigagent.plugin.builtin;

import io.pigagent.plugin.builtin.tool.CheckListTool;
import io.pigagent.tool.spi.ToolContext;

import java.util.List;

/**
 * Plugin contributing {@link CheckListTool} (the {@code createChecklist}/{@code completeItem}/{@code
 * showChecklist} tools). Extracted from the core tools module because it is a stateful convenience
 * helper rather than a core agent operation.
 */
public final class ChecklistPlugin extends AbstractToolPlugin {

    public ChecklistPlugin() {
        super("builtin:checklist");
    }

    @Override
    protected List<Object> createTools(ToolContext context) {
        return List.of(new CheckListTool());
    }
}
