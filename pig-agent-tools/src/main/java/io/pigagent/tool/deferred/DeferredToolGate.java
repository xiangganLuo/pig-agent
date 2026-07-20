package io.pigagent.tool.deferred;

import io.agentscope.core.tool.AgentTool;
import io.agentscope.core.tool.Toolkit;
import io.pigagent.core.tool.RevealTargets;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Applies a {@link DeferralPlan} to a live {@link Toolkit} using AgentScope's native tool groups, and
 * returns the populated {@link DeferredToolRegistry}. Analogous to {@code ToolAvailabilityGate}: a
 * bootstrap-time filter that decides what the model sees — but a <em>dynamic</em> one, since a
 * deferred tool can be brought back on demand via {@link #reveal}.
 *
 * <p>Mechanism (verified against AgentScope 1.0.12): a tool parked in an <b>inactive</b> group is
 * excluded from {@code getToolSchemas()} (token saving) and rejected at call time until the group is
 * activated. So:
 * <ul>
 *   <li><b>built-in / plugin tool</b> — create a per-tool inactive group and move the tool into it by
 *       re-registering its current {@link AgentTool} (loss-free: these carry no {@code mcpClientName}
 *       / extended model / preset params);</li>
 *   <li><b>MCP tool</b> — deactivate the {@code "mcp:<server>"} group it was registered into at
 *       attach time (re-registering an MCP tool would drop its {@code mcpClientName} and break
 *       {@code removeMcpClient} hot-removal, so we never do that).</li>
 * </ul>
 * Revealing is a pure group activation ({@link #reveal}); the tool object is never removed or
 * re-registered at reveal time.
 */
public final class DeferredToolGate {

    /** Prefix of the per-tool group a deferred built-in/plugin tool is parked in. */
    public static final String DEFER_GROUP_PREFIX = "deferred__";

    private static final Logger log = LoggerFactory.getLogger(DeferredToolGate.class);

    private DeferredToolGate() {
    }

    /**
     * Convenience: apply into a fresh registry (used by tests). See
     * {@link #applyTo(Toolkit, DeferralPlan, List, DeferredToolRegistry)}.
     */
    public static DeferredToolRegistry applyTo(Toolkit toolkit, DeferralPlan plan, List<ToolInfo> allTools) {
        return applyTo(toolkit, plan, allTools, new DeferredToolRegistry());
    }

    /**
     * Hide every tool named by {@code plan} in {@code toolkit}, populating {@code registry} with the
     * deferred tools' metadata (so a {@code tool_search} that already holds {@code registry} sees
     * them), and return it. Fail-safe: a tool that is absent or fails to move is skipped with a warn;
     * the rest still defer.
     */
    public static DeferredToolRegistry applyTo(Toolkit toolkit, DeferralPlan plan, List<ToolInfo> allTools,
                                               DeferredToolRegistry registry) {
        if (registry == null) {
            registry = new DeferredToolRegistry();
        }
        if (toolkit == null || plan == null || plan.isEmpty()) {
            return registry;
        }
        Map<String, ToolInfo> byName = new LinkedHashMap<>();
        if (allTools != null) {
            for (ToolInfo t : allTools) {
                byName.put(t.name(), t);
            }
        }
        Set<String> present = toolkit.getToolNames();
        Set<String> mcpGroupsToDeactivate = new LinkedHashSet<>();

        for (String name : plan.deferredToolNames()) {
            ToolInfo info = byName.get(name);
            if (info == null || !present.contains(name)) {
                log.debug("Deferred tool '{}' not present in toolkit; skipping", name);
                continue;
            }
            String group;
            if (info.isMcp()) {
                group = info.mcpGroup();
                mcpGroupsToDeactivate.add(group);
            } else {
                group = DEFER_GROUP_PREFIX + name;
                if (!parkBuiltinInGroup(toolkit, name, group, describe(info))) {
                    continue;
                }
            }
            registry.add(new DeferredTool(name, describe(info), info.keywords(), group));
            log.info("Tool '{}' deferred (hidden from initial schema, group '{}')", name, group);
        }

        if (!mcpGroupsToDeactivate.isEmpty()) {
            toolkit.updateToolGroups(new ArrayList<>(mcpGroupsToDeactivate), false);
        }
        return registry;
    }

    /**
     * Build the reveal seam for a toolkit + registry: activate the deferred tool's group and mark it
     * revealed. Used by {@code tool_search}.
     */
    public static DeferredToolReveal reveal(Toolkit toolkit, DeferredToolRegistry registry) {
        return toolName -> {
            Optional<DeferredTool> dt = registry.find(toolName);
            if (dt.isEmpty()) {
                return false;
            }
            toolkit.updateToolGroups(List.of(dt.get().groupName()), true);
            registry.markRevealed(toolName);
            return true;
        };
    }

    /**
     * Build the reveal seam over a {@link RevealTargets} broadcaster instead of a single toolkit — the
     * fix for reveal state not propagating across {@code Toolkit.copy()}. Because peers/subagents run on
     * independent-group-state copies, revealing must activate the tool's group on <em>every</em> live
     * toolkit (base + peer/subagent copies), so the tool becomes visible+callable in whichever copy the
     * current agent runs. Otherwise identical to {@link #reveal(Toolkit, DeferredToolRegistry)}.
     */
    public static DeferredToolReveal reveal(RevealTargets targets, DeferredToolRegistry registry) {
        return toolName -> {
            Optional<DeferredTool> dt = registry.find(toolName);
            if (dt.isEmpty()) {
                return false;
            }
            targets.activateGroup(dt.get().groupName());
            registry.markRevealed(toolName);
            return true;
        };
    }

    /** Create the per-tool inactive group (if absent) and move the tool into it. Fail-safe. */
    private static boolean parkBuiltinInGroup(Toolkit toolkit, String name, String group, String desc) {
        try {
            if (toolkit.getToolGroup(group) == null) {
                toolkit.createToolGroup(group, desc, false);
            }
            AgentTool tool = toolkit.getTool(name);
            if (tool == null) {
                return false;
            }
            toolkit.registration().agentTool(tool).group(group).apply();
            return true;
        } catch (RuntimeException e) {
            log.warn("Failed to defer tool '{}': {}", name, e.toString());
            return false;
        }
    }

    private static String describe(ToolInfo info) {
        String d = info.description();
        return (d == null || d.isBlank()) ? "deferred tool: " + info.name() : d;
    }
}
