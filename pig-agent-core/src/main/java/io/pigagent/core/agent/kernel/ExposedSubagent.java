package io.pigagent.core.agent.kernel;

import java.util.Objects;

/**
 * A subagent the active agent exposed to the user via {@code agent_spawn(expose_to_user=true)}
 * (subagent-online-switch). It carries just the addressing metadata the native
 * {@code SubagentExposedEvent} provides: the {@code id} (the gateway {@code subagentId} handle used to
 * route messages to it), the {@code agentId} (the subagent type), and an optional human {@code label}.
 *
 * <p>The frontend records these (via {@code AgentKernel.noteSubagentExposed}) as it sees the events on
 * the parent stream, then lists them ({@code /agent sub list}) and switches into one
 * ({@code /agent sub switch <id>}) — subsequent input streams to the subagent via
 * {@code AgentKernel.chatWithSubagent}. Immutable value type.
 */
public record ExposedSubagent(String id, String agentId, String label) {

    public ExposedSubagent {
        Objects.requireNonNull(id, "id");
    }

    /** A human-friendly display name: the label when set, else the agent type id. */
    public String display() {
        return (label != null && !label.isBlank()) ? label : agentId;
    }
}
