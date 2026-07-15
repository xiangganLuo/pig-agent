package io.pigagent.tool.deferred;

import java.util.Set;

/**
 * The inventory entry for one registered tool, fed to {@link DeferredToolPlanner} (to decide what to
 * defer) and {@link DeferredToolGate} (to apply it). Built in {@code AgentBootstrap} from the live
 * {@code Toolkit}'s schemas after all tools (built-in + plugin + MCP) have registered.
 *
 * @param name        the tool name
 * @param description the tool description (may be blank)
 * @param keywords    search keywords ({@link Keywords#from})
 * @param mcpGroup    if this is an MCP tool, the (active) tool-group it was registered into at
 *                    attach time ({@code "mcp:<server>"}); {@code null} for built-in/plugin tools.
 *                    A non-null value both marks the tool as MCP and names the group to deactivate
 *                    when deferring it.
 */
public record ToolInfo(String name, String description, Set<String> keywords, String mcpGroup) {

    public ToolInfo {
        keywords = keywords == null ? Set.of() : Set.copyOf(keywords);
    }

    /** Convenience: build with keywords derived from name + description. */
    public static ToolInfo of(String name, String description, String mcpGroup) {
        return new ToolInfo(name, description, Keywords.from(name, description), mcpGroup);
    }

    /** True when this tool is an MCP-server tool (deferred by deactivating its server group). */
    public boolean isMcp() {
        return mcpGroup != null && !mcpGroup.isBlank();
    }
}
