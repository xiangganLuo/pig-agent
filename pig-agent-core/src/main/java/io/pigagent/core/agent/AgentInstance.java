package io.pigagent.core.agent;

import java.util.Objects;

/**
 * A live, addressable agent: its {@link AgentSpec}, the built {@link PigAgent}, and a
 * lightweight lifecycle state. Generalizes what used to be the single {@code AgentHolder.get()}
 * into one of possibly many instances held by an {@link AgentRegistry}.
 */
public final class AgentInstance {

    public enum State { IDLE, RUNNING }

    private final String id;
    private final AgentSpec spec;
    private final PigAgent agent;
    private volatile State state = State.IDLE;

    public AgentInstance(String id, AgentSpec spec, PigAgent agent) {
        this.id = Objects.requireNonNull(id, "id");
        this.spec = Objects.requireNonNull(spec, "spec");
        this.agent = Objects.requireNonNull(agent, "agent");
    }

    public String id() {
        return id;
    }

    public AgentSpec spec() {
        return spec;
    }

    public PigAgent agent() {
        return agent;
    }

    public State state() {
        return state;
    }

    public void setState(State state) {
        this.state = Objects.requireNonNull(state, "state");
    }
}
