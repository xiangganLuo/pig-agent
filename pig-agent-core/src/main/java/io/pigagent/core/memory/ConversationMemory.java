package io.pigagent.core.memory;

import io.agentscope.core.ReActAgent;
import io.agentscope.core.memory.Memory;
import io.agentscope.core.message.Msg;
import io.agentscope.core.state.AgentState;
import io.agentscope.core.state.AgentStateStore;

import java.util.List;
import java.util.Objects;

/**
 * A thin {@link Memory} adapter over AgentScope 2.0's per-call conversation, which now lives on
 * {@code AgentState.getContext()} / {@code contextMutable()} rather than a standalone {@code Memory}
 * object (the 1.x {@code ReActAgent.getMemory()} was removed in 2.0).
 *
 * <p>It exists so the still-{@code Memory}-shaped seams in pig-agent — chiefly
 * {@code CompressionService}, which reads/rewrites the live conversation via
 * {@code getMessages()/clear()/addMessage(...)} — keep working against the migrated agent while the
 * full compression/state re-architecture (native {@code CompactionConfig}) is deferred to a later
 * phase. Every operation resolves the agent's <em>current</em> {@code AgentState} on demand, so it
 * reflects and mutates the real conversation the model will see on the next reasoning step.
 *
 * <p>Scope note (av2 Phase 0): {@link #saveTo}/{@link #loadFrom} are not exercised by pig-agent
 * (persistence goes through {@code PigAgent.saveTo}/{@code AgentStateStore}); they delegate to the
 * agent's own state persistence and are left minimal.
 *
 * <p><b>Session scoping (av2 Phase 3).</b> The default constructor views the agent's <em>default</em>
 * session state ({@code getAgentState()}); the {@code (userId, sessionId)} constructor views a
 * specific session slot ({@code getAgentState(userId, sessionId)}, which the agent caches per slot,
 * so mutations here reach the same conversation the next same-session {@code call} will see). The
 * session-scoped view is the seam a per-session compression path uses (Phase 4).
 */
public final class ConversationMemory implements Memory {

    private static final String DEFAULT_USER = "pig";

    private final ReActAgent agent;
    private final String userId;   // null => default-session view (getAgentState())
    private final String sessionId; // null => default-session view

    public ConversationMemory(ReActAgent agent) {
        this(agent, null, null);
    }

    /** View over a specific session slot; a null {@code sessionId} falls back to the default view. */
    public ConversationMemory(ReActAgent agent, String userId, String sessionId) {
        this.agent = Objects.requireNonNull(agent, "agent");
        this.userId = userId;
        this.sessionId = sessionId;
    }

    private AgentState state() {
        return sessionId == null ? agent.getAgentState() : agent.getAgentState(userId, sessionId);
    }

    private List<Msg> context() {
        return state().contextMutable();
    }

    @Override
    public void addMessage(Msg msg) {
        context().add(msg);
    }

    @Override
    public List<Msg> getMessages() {
        return state().getContext();
    }

    @Override
    public void deleteMessage(int index) {
        List<Msg> ctx = context();
        if (index >= 0 && index < ctx.size()) {
            ctx.remove(index);
        }
    }

    @Override
    public void clear() {
        context().clear();
    }

    @Override
    public void saveTo(AgentStateStore store, String userId, String sessionId) {
        agent.saveAgentState(userId, sessionId);
    }

    @Override
    public void loadFrom(AgentStateStore store, String userId, String sessionId) {
        // No-op: the 2.0 state store auto-loads per (userId, sessionId) on the next call().
    }
}
