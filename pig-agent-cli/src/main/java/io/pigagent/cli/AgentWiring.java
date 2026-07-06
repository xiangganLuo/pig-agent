package io.pigagent.cli;

import io.agentscope.core.tool.Toolkit;
import io.pigagent.config.PermissionMode;
import io.pigagent.tool.permission.PermissionDeniedTool;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Per-agent wiring helpers used to turn an {@code AgentSpec} into concrete building blocks:
 * a tool subset and a permission mode. Kept separate from the CLI bootstrap so the pure logic
 * is unit-testable.
 */
public final class AgentWiring {

    private AgentWiring() {
    }

    /**
     * The toolkit an agent should use: the shared full toolkit when {@code toolNames} is empty
     * (= "all tools"), otherwise a {@link Toolkit#copy()} restricted to the whitelisted names.
     * Unknown names are silently ignored; the permission deny sentinel is always kept so the
     * permission hook can still rewrite vetoed calls to it.
     */
    public static Toolkit toolkitFor(Toolkit full, List<String> toolNames) {
        if (toolNames == null || toolNames.isEmpty()) {
            return full;
        }
        Toolkit sub = full.copy();
        Set<String> keep = new HashSet<>(toolNames);
        keep.add(PermissionDeniedTool.TOOL_NAME);
        for (String name : new HashSet<>(sub.getToolNames())) {
            if (!keep.contains(name)) {
                sub.removeTool(name);
            }
        }
        return sub;
    }

    /**
     * Parse an {@code AgentSpec.permissionMode} string into a {@link PermissionMode}. Null, blank,
     * or unrecognized values yield {@code null}, meaning "fall back to the global default mode".
     */
    public static PermissionMode permissionModeOf(String mode) {
        return PermissionMode.fromString(mode, null);
    }
}
