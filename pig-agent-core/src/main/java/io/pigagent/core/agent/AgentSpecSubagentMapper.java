package io.pigagent.core.agent;

import io.agentscope.harness.agent.subagent.SubagentDeclaration;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Objects;

/**
 * Maps pig's declarative peer {@link AgentSpec} → a native {@link SubagentDeclaration} (av2 Phase 6a),
 * so a pig-declared agent can be <em>both</em> a switchable peer (via {@code AgentRegistry} + {@code
 * /agent use}) <em>and</em> a delegable subagent (spawnable by the active agent via {@code agent_spawn}).
 *
 * <p><b>These are two orthogonal concepts and this mapper does not conflate them.</b> A peer is the
 * <em>active</em> agent you switch to; a subagent is a transient <em>child</em> the active agent
 * delegates a subtask to and gets a result back from. The mapper merely lets the same declarative spec
 * feed both surfaces.
 *
 * <p><b>Field mapping</b> (grounded in {@code AgentSpecLoader.parse}, which is how native
 * {@code workspace/subagents/<id>.md} files are parsed):
 * <ul>
 *   <li>{@code name} ← {@code spec.id()} — the {@code agent_id} the model passes to {@code agent_spawn}.</li>
 *   <li>{@code description} ← name + first prompt line — <em>required</em> by native; the model reads it
 *       to decide whether to delegate. Never blank (falls back to the id).</li>
 *   <li>{@code inlineAgentsBody} ← {@code spec.sysPrompt()} — the child's persona/system prompt (native
 *       uses the markdown body as the subagent's system prompt).</li>
 *   <li>{@code tools} ← {@code spec.toolNames()} when a subset is declared — the child's tool allowlist
 *       (empty = inherit all the parent's tools).</li>
 *   <li><b>permission:</b> native subagents have <em>no per-agent permission field</em> (permission is
 *       {@code PermissionMode} + rules, not a spec attribute). The declaration <em>requests</em>
 *       {@code inheritParentPermissions(true)} so that, once the runtime honors it, the parent's DENY
 *       rules propagate to the child and the spec's tool subset narrows what it can attempt.
 *       <b>Important 2.0.0 caveat:</b> {@code inheritParentPermissions} is declared but <em>inert</em>
 *       in AgentScope 2.0.0 (no harness class reads it) — a spawned child actually runs under its own
 *       permissive context and does NOT inherit the parent's DENY rules. Wiring real inheritance (a
 *       custom {@code subagentFactory} that injects the parent's {@code PermissionContextState}) is a
 *       Phase-6b item; until then subagent delegation defaults OFF ({@code subagents.enabled}).</li>
 * </ul>
 * Pure + immutable; no side effects. The concrete wiring (which specs to expose) lives in the CLI.
 */
public final class AgentSpecSubagentMapper {

    private AgentSpecSubagentMapper() {
    }

    /** Map one peer spec to a spawnable subagent declaration (see class javadoc for the field mapping). */
    public static SubagentDeclaration toDeclaration(AgentSpec spec) {
        Objects.requireNonNull(spec, "spec");
        SubagentDeclaration.Builder b = SubagentDeclaration.builder()
                .name(spec.id())
                .description(describe(spec))
                // Parent DENY rules propagate to the child — delegation never escalates authority.
                .inheritParentPermissions(true);
        String prompt = spec.sysPrompt();
        if (prompt != null && !prompt.isBlank()) {
            b.inlineAgentsBody(prompt);
        }
        if (!spec.usesAllTools()) {
            b.tools(List.copyOf(spec.toolNames()));
        }
        return b.build();
    }

    /**
     * Map a collection of peer specs to subagent declarations, skipping {@code null}s, the given
     * {@code excludeId} (typically the active agent — an agent need not spawn itself), and autonomous
     * specs (scheduled digital-employee runners, not interactive delegation targets). Fault-tolerant:
     * a spec that fails to map is skipped, never aborting the whole list (mirroring pig's repository
     * fault-tolerance). Order is preserved; the result is a fresh mutable list.
     */
    public static List<SubagentDeclaration> toDeclarations(Collection<AgentSpec> specs, String excludeId) {
        List<SubagentDeclaration> out = new ArrayList<>();
        if (specs == null) {
            return out;
        }
        for (AgentSpec s : specs) {
            if (s == null || s.isAutonomous()) {
                continue;
            }
            if (excludeId != null && excludeId.equals(s.id())) {
                continue;
            }
            try {
                out.add(toDeclaration(s));
            } catch (RuntimeException e) {
                // Skip a spec that cannot be mapped rather than break peer discovery.
                continue;
            }
        }
        return out;
    }

    /** A non-blank delegation description: the agent name plus the first non-blank prompt line. */
    private static String describe(AgentSpec spec) {
        String base = (spec.name() == null || spec.name().isBlank()) ? spec.id() : spec.name().strip();
        String firstLine = firstNonBlankLine(spec.sysPrompt());
        return firstLine == null ? base : base + " — " + firstLine;
    }

    private static String firstNonBlankLine(String text) {
        if (text == null) {
            return null;
        }
        for (String line : text.split("\\R")) {
            String stripped = line.strip();
            if (!stripped.isEmpty() && !stripped.equals("---")) {
                return stripped.length() > 200 ? stripped.substring(0, 200) : stripped;
            }
        }
        return null;
    }
}
