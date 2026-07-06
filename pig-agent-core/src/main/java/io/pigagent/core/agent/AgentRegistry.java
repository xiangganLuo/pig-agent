package io.pigagent.core.agent;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Runtime registry of {@link AgentInstance}s, replacing the single-agent {@link AgentHolder}.
 *
 * <p>Exactly one instance is "active" at a time. The registry keeps the supplied
 * {@link AgentHolder} pointed at the active instance's {@link PigAgent}, so every existing
 * component that reads {@code agentHolder.get()} (REPL, session, channel, compression) keeps
 * working unchanged — the holder becomes a live view of the active agent.
 */
public final class AgentRegistry {

    private final Map<String, AgentInstance> instances = new LinkedHashMap<>();
    private final AgentHolder holder;
    private String activeId;

    public AgentRegistry(AgentHolder holder) {
        this.holder = Objects.requireNonNull(holder, "holder");
    }

    /** Register an instance; the first one registered becomes active. */
    public AgentInstance register(AgentInstance instance) {
        instances.put(instance.id(), instance);
        if (activeId == null) {
            setActive(instance.id());
        }
        return instance;
    }

    /** Make the given agent active and point the holder at it. Returns false if unknown. */
    public boolean setActive(String id) {
        AgentInstance instance = instances.get(id);
        if (instance == null) {
            return false;
        }
        activeId = id;
        holder.set(instance.agent());
        return true;
    }

    public Optional<AgentInstance> get(String id) {
        return Optional.ofNullable(instances.get(id));
    }

    public Optional<AgentInstance> active() {
        return activeId == null ? Optional.empty() : get(activeId);
    }

    public String activeId() {
        return activeId;
    }

    public boolean contains(String id) {
        return instances.containsKey(id);
    }

    public List<AgentInstance> list() {
        return new ArrayList<>(instances.values());
    }

    /** Remove an instance; if it was active, fall back to any remaining instance. */
    public void remove(String id) {
        instances.remove(id);
        if (id.equals(activeId)) {
            activeId = null;
            instances.keySet().stream().findFirst().ifPresent(this::setActive);
        }
    }
}
