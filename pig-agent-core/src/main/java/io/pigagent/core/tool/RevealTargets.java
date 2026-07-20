package io.pigagent.core.tool;

import io.agentscope.core.tool.Toolkit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.WeakHashMap;

/**
 * A shared set of live {@link Toolkit} instances that a deferred-tool <em>reveal</em> must activate a
 * group on — the fix for the {@code deferred-tools} "reveal state does not propagate across
 * {@code Toolkit.copy()}" limitation.
 *
 * <p><b>Why this exists.</b> {@code Toolkit.copy()} gives each copy an <em>independent</em> tool-group
 * active-state (verified: activating a group on the base toolkit does not affect a copy, and vice-versa).
 * Peers ({@code AgentWiring.toolkitFor}) and subagents ({@code PigAgent} child toolkits) run on such
 * copies, but the single {@code tool_search} tool object (shared across copies) carries one reveal seam
 * bound to the <em>base</em> toolkit — so a reveal fired from a copy's turn activated the group on the
 * base, not on the copy the agent actually runs, and the tool stayed hidden there. Broadcasting the
 * activation to every registered toolkit fixes that: activating a group that a given toolkit does not
 * have is a harmless no-op, and every copy carries the same group definitions.
 *
 * <p>Placed in the kernel ({@code io.pigagent.core.tool}) so both the CLI wiring (base + peer copies)
 * and the core subagent factory (child copies) can register into one instance. Thread-safe; holds
 * <b>weak</b> references so short-lived subagent copies never leak.
 */
public final class RevealTargets {

    private static final Logger log = LoggerFactory.getLogger(RevealTargets.class);

    private final Set<Toolkit> toolkits = Collections.newSetFromMap(new WeakHashMap<>());

    /** Register a live toolkit (base or a {@code copy()}) as a reveal target. Null / duplicates are ignored. */
    public synchronized void register(Toolkit toolkit) {
        if (toolkit != null) {
            toolkits.add(toolkit);
        }
    }

    /**
     * Activate {@code group} on every registered toolkit, so a tool parked in that (inactive) group
     * re-enters the schema and becomes callable regardless of which copy the current agent runs on.
     * Fault-tolerant: a per-toolkit failure is logged and skipped (never breaks {@code tool_search}).
     */
    public synchronized void activateGroup(String group) {
        if (group == null || group.isBlank()) {
            return;
        }
        for (Toolkit tk : new ArrayList<>(toolkits)) {
            try {
                tk.updateToolGroups(List.of(group), true);
            } catch (RuntimeException e) {
                log.warn("Failed to activate group '{}' on a toolkit: {}", group, e.toString());
            }
        }
    }

    /** Number of live registered toolkits (test seam / diagnostics). */
    public synchronized int size() {
        return toolkits.size();
    }
}
