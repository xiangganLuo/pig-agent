package io.pigagent.core.memory;

import io.agentscope.core.ReActAgent;
import io.agentscope.core.memory.Memory;
import io.agentscope.core.message.Msg;
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
 */
public final class ConversationMemory implements Memory {

    private static final String DEFAULT_USER = "pig";

    private final ReActAgent agent;

    public ConversationMemory(ReActAgent agent) {
        this.agent = Objects.requireNonNull(agent, "agent");
    }

    private List<Msg> context() {
        return agent.getAgentState().contextMutable();
    }

    @Override
    public void addMessage(Msg msg) {
        context().add(msg);
    }

    @Override
    public List<Msg> getMessages() {
        return agent.getAgentState().getContext();
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
