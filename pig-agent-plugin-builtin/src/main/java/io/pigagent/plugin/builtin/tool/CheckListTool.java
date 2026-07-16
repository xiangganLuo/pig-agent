package io.pigagent.plugin.builtin.tool;

import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.ToolParam;
import java.util.*;

public final class CheckListTool {
    private final Map<String, List<Item>> checklists = new LinkedHashMap<>();

    @Tool(description = "Create a new checklist with items")
    public String createChecklist(
            @ToolParam(name = "name", description = "Checklist name") String name,
            @ToolParam(name = "items", description = "Comma-separated items") String items) {
        List<Item> list = new ArrayList<>();
        for (String item : items.split(",")) list.add(new Item(item.trim(), false));
        checklists.put(name, list);
        return "Created checklist '%s' with %d items.".formatted(name, list.size());
    }

    @Tool(description = "Mark a checklist item as complete")
    public String completeItem(
            @ToolParam(name = "name", description = "Checklist name") String name,
            @ToolParam(name = "item_index", description = "Item index (0-based)") int index) {
        List<Item> list = checklists.get(name);
        if (list == null) return "Checklist not found: " + name;
        if (index < 0 || index >= list.size()) return "Invalid index: " + index;
        list.set(index, new Item(list.get(index).text(), true));
        return "Marked item %d as complete.".formatted(index);
    }

    @Tool(readOnly = true, description = "Show the current state of a checklist")
    public String showChecklist(@ToolParam(name = "name", description = "Checklist name") String name) {
        List<Item> list = checklists.get(name);
        if (list == null) return "Checklist not found: " + name;
        StringBuilder sb = new StringBuilder("## " + name + "\n");
        for (int i = 0; i < list.size(); i++) {
            var item = list.get(i);
            sb.append(item.done() ? "- [x] " : "- [ ] ").append(item.text()).append("\n");
        }
        return sb.toString();
    }

    private record Item(String text, boolean done) {}
}
