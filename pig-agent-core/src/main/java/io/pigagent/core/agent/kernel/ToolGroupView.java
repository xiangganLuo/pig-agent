package io.pigagent.core.agent.kernel;

import java.util.List;

/**
 * A read-only view of one tool group (capability pack) for {@code /tools groups}
 * ({@code tools-observability}, T3): the group name, whether it is currently active (its tools are in
 * the model schema), and the member tool names.
 *
 * @param name   the group name (e.g. {@code deferred__webSearch} or {@code mcp:<server>})
 * @param active whether the group is active (its member tools are visible/callable)
 * @param tools  the member tool names (never null)
 */
public record ToolGroupView(String name, boolean active, List<String> tools) {

    public ToolGroupView {
        tools = tools == null ? List.of() : List.copyOf(tools);
    }
}
