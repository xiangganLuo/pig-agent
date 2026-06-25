package io.pigagent.core.agent;

import java.util.Objects;

/**
 * Mutable holder for the live {@link PigAgent}.
 *
 * <p>The AgentScope model is fixed at build time, so switching models at runtime means
 * rebuilding the agent. Everything that needs the current agent reads it through this holder
 * (rather than capturing a {@code PigAgent} reference), so a swap is immediately visible to
 * the REPL, channels, the session manager, and compression.
 */
public final class AgentHolder {

    private volatile PigAgent agent;

    public AgentHolder(PigAgent agent) {
        this.agent = Objects.requireNonNull(agent, "agent");
    }

    public PigAgent get() {
        return agent;
    }

    public void set(PigAgent agent) {
        this.agent = Objects.requireNonNull(agent, "agent");
    }
}
