package io.pigagent.cli.repl;

/**
 * REPL-local state for the "currently switched-into exposed subagent" (subagent-online-switch).
 *
 * <p>When the user runs {@code /agent sub switch <id>} the REPL records that id here; while it is set,
 * {@code AgentRepl.runTurn} routes plain chat input to the subagent (via {@code
 * AgentKernel.chatWithSubagent}) instead of the parent agent. {@code /agent sub back} clears it and
 * returns to the parent. The set of <em>which</em> subagents exist is owned by the kernel ({@code
 * AgentKernel.listSubagents}); this holds only the small "am I talking to a child, and which one"
 * pointer, shared between {@code AgentRepl} (the run loop) and {@code AgentCommand} (the {@code
 * /agent sub} command) via {@code ReplContext}.
 *
 * <p>Not a record: it is deliberately mutable single-pointer state. {@code volatile} because the REPL
 * read loop and a command handler may touch it from different call paths.
 */
public final class SubagentSwitchState {

    private volatile String currentId;

    /** Whether the REPL is currently switched into a subagent (input routes to the child). */
    public boolean isActive() {
        return currentId != null;
    }

    /** The subagent id currently switched into, or {@code null} when talking to the parent. */
    public String current() {
        return currentId;
    }

    /** Switch into the given subagent id (subsequent input routes to it). Ignores null/blank. */
    public void switchTo(String subagentId) {
        if (subagentId != null && !subagentId.isBlank()) {
            this.currentId = subagentId;
        }
    }

    /** Return to the parent agent (clear the switch). Idempotent. */
    public void back() {
        this.currentId = null;
    }
}
