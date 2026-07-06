package io.pigagent.core.agent.kernel;

import io.agentscope.core.agent.Event;
import io.agentscope.core.message.Msg;
import io.pigagent.core.agent.AgentInstance;
import io.pigagent.core.agent.AgentInstanceFactory;
import io.pigagent.core.agent.AgentRegistry;
import io.pigagent.core.agent.AgentSpec;
import io.pigagent.core.agent.AgentSpecRepository;
import io.pigagent.core.agent.runner.AgentReport;
import io.pigagent.core.agent.runner.AgentRunner;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Sinks;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * The single façade the kernel exposes to frontends (CLI now, Web later). Frontends depend on
 * {@code AgentKernel} + {@link KernelEvent} only — never on the internal {@link AgentRegistry} /
 * {@link AgentInstanceFactory} / {@link AgentRunner}. "Add a frontend = add an adapter"; the
 * internals stay unchanged.
 *
 * <p>Events are published on a multicast sink: no subscribers means emissions are dropped (zero
 * cost); many subscribers (CLI status, Web SSE) each receive them.
 */
public final class AgentKernel {

    private final AgentRegistry registry;
    private final AgentSpecRepository repository;
    private final AgentInstanceFactory instanceFactory;
    private final AgentRunner runner; // nullable (no digital-employee runs wired)
    private final Sinks.Many<KernelEvent> events =
            Sinks.many().multicast().onBackpressureBuffer(256, false);

    public AgentKernel(AgentRegistry registry, AgentSpecRepository repository,
                       AgentInstanceFactory instanceFactory, AgentRunner runner) {
        this.registry = Objects.requireNonNull(registry, "registry");
        this.repository = Objects.requireNonNull(repository, "repository");
        this.instanceFactory = Objects.requireNonNull(instanceFactory, "instanceFactory");
        this.runner = runner;
    }

    public List<AgentInstance> listAgents() {
        return registry.list();
    }

    public Optional<AgentInstance> getAgent(String id) {
        return registry.get(id);
    }

    public String activeId() {
        return registry.activeId();
    }

    /** Switch the active agent; emits {@code AGENT_SWITCHED}. Returns false if unknown. */
    public boolean useAgent(String id) {
        boolean ok = registry.setActive(id);
        if (ok) {
            emit(KernelEvent.Type.AGENT_SWITCHED, id, id);
        }
        return ok;
    }

    /** Create, persist and register an agent; emits {@code AGENT_CREATED}. */
    public AgentInstance createAgent(AgentSpec spec) {
        repository.save(spec);
        AgentInstance instance = instanceFactory.create(spec);
        registry.register(instance);
        emit(KernelEvent.Type.AGENT_CREATED, spec.id(), spec.name());
        return instance;
    }

    /** Update an agent's spec (rebuild + persist); re-points the holder if it is active. */
    public AgentInstance updateAgent(AgentSpec spec) {
        repository.save(spec);
        AgentInstance rebuilt = instanceFactory.create(spec);
        registry.register(rebuilt);
        if (spec.id().equals(registry.activeId())) {
            registry.setActive(spec.id());
        }
        return rebuilt;
    }

    public void deleteAgent(String id) {
        registry.remove(id);
        repository.deleteById(id);
        emit(KernelEvent.Type.AGENT_DELETED, id, id);
    }

    /** Stream a chat turn on the given agent (or the active one if it is not the active id). */
    public Flux<Event> chat(String agentId, Msg msg) {
        AgentInstance instance = registry.get(agentId).orElse(registry.active().orElse(null));
        if (instance == null) {
            return Flux.error(new IllegalStateException("No agent available: " + agentId));
        }
        emit(KernelEvent.Type.CHAT_STARTED, instance.id(), "");
        return instance.agent().stream(msg);
    }

    /** Trigger one autonomous run of an agent now; emits RUN_STARTED/RUN_FINISHED/REPORT. */
    public Optional<AgentReport> runNow(String agentId) {
        if (runner == null) {
            return Optional.empty();
        }
        AgentSpec spec = repository.findById(agentId).orElse(null);
        if (spec == null) {
            return Optional.empty();
        }
        emit(KernelEvent.Type.RUN_STARTED, agentId, spec.name());
        Optional<AgentReport> report = runner.run(spec);
        report.ifPresent(r -> {
            emit(KernelEvent.Type.RUN_FINISHED, agentId, String.valueOf(r.outcome()));
            emit(KernelEvent.Type.REPORT, agentId, String.valueOf(r.outcome()));
        });
        return report;
    }

    /**
     * D4: a channel turn is surfaced through the façade for observability (Web SSE, status), but the
     * channel keeps its OWN agent holder + channel-mode permission track — it is deliberately NOT a
     * switchable kernel agent (that would leak the no-confirmer channel agent into {@code /agent use}).
     * So the channel is "façade-visible, separate-track": events flow, ownership does not.
     */
    public void noteChannelChat(String channelId) {
        emit(KernelEvent.Type.CHAT_STARTED, "channel:" + channelId, channelId);
    }

    /** Subscribe to kernel lifecycle events (hot, multicast). */
    public Flux<KernelEvent> subscribeEvents() {
        return events.asFlux();
    }

    private void emit(KernelEvent.Type type, String agentId, String message) {
        events.tryEmitNext(KernelEvent.of(type, agentId, message));
    }
}
