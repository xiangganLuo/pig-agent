package io.pigagent.core.agent.kernel;

import java.util.List;

/**
 * Runtime tool-management seam exposed through the {@link AgentKernel} façade
 * ({@code tools-observability}, T3), so {@code /tools} (and future frontends) drive tool visibility +
 * availability re-evaluation without depending on the internal toolkit / deferred registry /
 * availability gate. The concrete implementation lives in the wiring layer ({@code AgentBootstrap} →
 * {@code ToolsConsole}) where those tools-module types are visible; the kernel package sees only this
 * interface and its plain result types.
 *
 * <p>All methods are operator-facing and return a human-readable, <b>credential-free</b> result — an
 * availability reason names only the missing prerequisite (aligned with the availability report
 * convention), never a secret value.
 */
public interface ToolAdmin {

    /** The tool groups (capability packs) — name, active state and member tool names. */
    List<ToolGroupView> groups();

    /**
     * Make a tool visible at runtime: reveal it if it is deferred (activate its group), or explain
     * that an availability-hidden tool needs its prerequisite + a {@code refresh} (never opens it
     * while the prerequisite is missing).
     */
    ToolActionResult enable(String toolName);

    /** Hide a currently-visible tool at runtime by deferring it (parking it in an inactive group). */
    ToolActionResult disable(String toolName);

    /**
     * Re-evaluate tool availability at runtime (触发式): a now-satisfied prerequisite re-surfaces a
     * previously hidden tool without a restart. When the visible set changes, the agents are rebuilt
     * (reusing the MCP tool-change rebuild mechanism) so the re-surfaced tools get their permission
     * rules.
     */
    ToolActionResult refresh();
}
