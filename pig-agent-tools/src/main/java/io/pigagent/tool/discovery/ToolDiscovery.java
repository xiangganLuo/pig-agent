package io.pigagent.tool.discovery;

import io.agentscope.core.tool.Tool;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

public final class ToolDiscovery {
    private final List<ToolEntry> entries = new ArrayList<>();

    public void register(Object toolInstance) {
        for (Method method : toolInstance.getClass().getDeclaredMethods()) {
            Tool annotation = method.getAnnotation(Tool.class);
            if (annotation != null) {
                entries.add(new ToolEntry(toolInstance, method, annotation.name(), annotation.description()));
            }
        }
    }

    public List<ToolEntry> search(String query) {
        String lower = query.toLowerCase();
        return entries.stream()
                .filter(e -> e.name().toLowerCase().contains(lower) || e.description().toLowerCase().contains(lower))
                .toList();
    }

    public List<ToolEntry> getAll() { return List.copyOf(entries); }

    public record ToolEntry(Object instance, Method method, String name, String description) {}
}
