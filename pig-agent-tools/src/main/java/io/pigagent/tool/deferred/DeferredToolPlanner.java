package io.pigagent.tool.deferred;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Pure decision function (Strategy) that decides which tools to defer, given the config and the full
 * tool inventory. No side effects, no Toolkit — fully unit-testable offline.
 *
 * <p>Rule (only when {@code enabled}):
 * <ul>
 *   <li><b>explicit list</b> — any inventory tool whose name is listed is deferred;</li>
 *   <li><b>auto-defer-mcp threshold</b> — when {@code autoDeferMcp} and the total tool count exceeds
 *       {@code threshold}, every MCP tool ({@link ToolInfo#isMcp()}) is deferred.</li>
 * </ul>
 * The two are unioned. {@code tool_search} itself is never deferred (it is the discovery entry
 * point and must stay visible).
 */
public final class DeferredToolPlanner {

    /** The discovery tool that must always remain visible. */
    public static final String TOOL_SEARCH = "tool_search";

    private DeferredToolPlanner() {
    }

    public static DeferralPlan plan(boolean enabled, List<String> explicit, boolean autoDeferMcp,
                                    int threshold, List<ToolInfo> allTools) {
        if (!enabled || allTools == null || allTools.isEmpty()) {
            return DeferralPlan.empty();
        }
        Set<String> present = new LinkedHashSet<>();
        for (ToolInfo t : allTools) {
            present.add(t.name());
        }
        Set<String> deferred = new LinkedHashSet<>();

        if (explicit != null) {
            for (String name : explicit) {
                if (name != null && present.contains(name) && !TOOL_SEARCH.equals(name)) {
                    deferred.add(name);
                }
            }
        }

        if (autoDeferMcp && allTools.size() > threshold) {
            for (ToolInfo t : allTools) {
                if (t.isMcp() && !TOOL_SEARCH.equals(t.name())) {
                    deferred.add(t.name());
                }
            }
        }

        return new DeferralPlan(deferred);
    }
}
