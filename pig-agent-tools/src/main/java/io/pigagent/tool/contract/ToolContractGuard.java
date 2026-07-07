package io.pigagent.tool.contract;

import io.agentscope.core.tool.AgentTool;
import io.agentscope.core.tool.Toolkit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashSet;

/**
 * Installs the dispatch-layer contract guard over a {@link Toolkit}: every registered tool is
 * replaced by a {@link GuardedAgentTool} wrapping it, so any exception a tool lets escape is turned
 * into a canonical {@code {"error":...}} result instead of aborting the turn. Re-registering an
 * {@link AgentTool} under the same name replaces it in place (schema/name/parameters are preserved
 * by the decorator), so the guard is transparent to callers.
 *
 * <p>Call this once after all built-in tools have been registered. It is idempotent: an
 * already-guarded tool is left untouched, so it is safe to call again.
 */
public final class ToolContractGuard {

    private static final Logger log = LoggerFactory.getLogger(ToolContractGuard.class);

    private ToolContractGuard() {
    }

    /** Wrap every currently-registered tool of {@code toolkit} in a {@link GuardedAgentTool}. */
    public static void install(Toolkit toolkit) {
        if (toolkit == null) {
            return;
        }
        int wrapped = 0;
        for (String name : new HashSet<>(toolkit.getToolNames())) {
            AgentTool tool = toolkit.getTool(name);
            if (tool == null || tool instanceof GuardedAgentTool) {
                continue;
            }
            toolkit.registration().agentTool(new GuardedAgentTool(tool)).apply();
            wrapped++;
        }
        if (wrapped > 0) {
            log.debug("Installed tool contract guard over {} tool(s)", wrapped);
        }
    }
}
