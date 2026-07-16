package io.pigagent.core.agent;

import io.agentscope.core.permission.AdditionalWorkingDirectory;
import io.agentscope.core.permission.PermissionBehavior;
import io.agentscope.core.permission.PermissionContextState;
import io.agentscope.core.permission.PermissionMode;
import io.agentscope.core.permission.PermissionRule;

import java.util.List;
import java.util.Map;

/**
 * Derives the {@link PermissionContextState} a spawned <b>subagent (child)</b> runs under from its
 * parent's context (av2 Phase-6b — subagent permission inheritance).
 *
 * <p><b>Why pig has to do this.</b> AgentScope 2.0.0's
 * {@code SubagentDeclaration.inheritParentPermissions} is <em>declared but inert</em> (no harness class
 * reads it — whole-jar {@code javap} scan), so a natively-spawned child runs under its own permissive
 * context and can execute a tool the parent denied — a permission-escape. pig closes this by building
 * every spawnable child itself (via {@code HarnessAgent.Builder.subagentFactory}) and installing the
 * context this method derives, so the parent's DENY rules bind the child and delegation can never
 * exceed the parent's authority.
 *
 * <p><b>Derivation (conservative, fail-closed).</b> A spawned child has <em>no confirmer</em>, so any
 * interactive ASK must fail closed:
 * <ul>
 *   <li><b>Base mode:</b> {@code EXPLORE} → {@code EXPLORE} (read-only inherited — a plan-mode parent's
 *       child stays read-only); {@code BYPASS} → {@code BYPASS} (explicit full trust; DENY rules still
 *       bind); everything else ({@code DEFAULT}/{@code ACCEPT_EDITS}/{@code DONT_ASK}) →
 *       {@code DONT_ASK} (unruled tools ask→deny — the non-interactive fail-closed baseline).</li>
 *   <li><b>DENY rules:</b> copied verbatim — the parent's denials bind the child (the headline
 *       guarantee).</li>
 *   <li><b>ASK rules → DENY:</b> a child cannot prompt, so an inherited ASK becomes a hard DENY.</li>
 *   <li><b>ALLOW rules + working directories:</b> copied — the child never gains authority the parent
 *       lacked, so preserving the parent's explicit grants is safe (and never widens them).</li>
 * </ul>
 * Pure + immutable; returns a fresh context. A {@code null} parent yields a minimal fail-closed
 * context ({@code DONT_ASK}, no rules) so a child is never <em>more</em> permissive than the parent.
 */
public final class SubagentPermissions {

    private SubagentPermissions() {
    }

    /** Derive the fail-closed child context inheriting the parent's DENY rules (see class javadoc). */
    public static PermissionContextState deriveChildContext(PermissionContextState parent) {
        PermissionContextState.Builder b = PermissionContextState.builder();
        if (parent == null) {
            return b.mode(PermissionMode.DONT_ASK).build();
        }
        b.mode(childMode(parent.getMode()));
        for (Map.Entry<String, AdditionalWorkingDirectory> wd : parent.getWorkingDirectories().entrySet()) {
            b.addWorkingDirectory(wd.getKey(), wd.getValue());
        }
        copyRules(parent.getAllowRules(), b, null);
        copyRules(parent.getDenyRules(), b, PermissionBehavior.DENY);
        // A child has no confirmer: an inherited ASK becomes a hard DENY (fail-closed).
        copyRules(parent.getAskRules(), b, PermissionBehavior.DENY);
        return b.build();
    }

    /** The child's base mode: read-only/plan and explicit-trust are preserved; all else fail-closed. */
    private static PermissionMode childMode(PermissionMode parentMode) {
        if (parentMode == PermissionMode.EXPLORE) {
            return PermissionMode.EXPLORE;
        }
        if (parentMode == PermissionMode.BYPASS) {
            return PermissionMode.BYPASS;
        }
        return PermissionMode.DONT_ASK;
    }

    /**
     * Copy each rule into the child builder. {@code forceBehavior} non-null rewrites every copied rule
     * to that behavior (used to downgrade ASK→DENY and to keep DENY as DENY); {@code null} preserves
     * the rule's own behavior (used for ALLOW). Routing is by the <em>target</em> behavior so an
     * ASK rule lands in the deny map.
     */
    private static void copyRules(Map<String, List<PermissionRule>> rules,
                                  PermissionContextState.Builder b, PermissionBehavior forceBehavior) {
        for (Map.Entry<String, List<PermissionRule>> e : rules.entrySet()) {
            String toolName = e.getKey();
            for (PermissionRule r : e.getValue()) {
                PermissionBehavior behavior = forceBehavior != null ? forceBehavior : r.behavior();
                PermissionRule copy = new PermissionRule(
                        r.toolName(), r.ruleContent(), behavior, childSource(r.source()));
                switch (behavior) {
                    case ALLOW -> b.addAllowRule(toolName, copy);
                    case DENY -> b.addDenyRule(toolName, copy);
                    case ASK -> b.addAskRule(toolName, copy);
                    default -> { /* PASSTHROUGH never persisted as a rule; ignore defensively */ }
                }
            }
        }
    }

    private static String childSource(String parentSource) {
        String base = parentSource == null ? "pig" : parentSource;
        return base.endsWith(":subagent") ? base : base + ":subagent";
    }
}
