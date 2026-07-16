package io.pigagent.core.agent;

import io.agentscope.core.ReActAgent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.hook.Hook;
import io.agentscope.core.memory.LongTermMemory;
import io.agentscope.core.memory.Memory;
import io.agentscope.core.message.Msg;
import io.agentscope.core.model.Model;
import io.agentscope.core.state.AgentStateStore;
import io.agentscope.core.state.InMemoryAgentStateStore;
import io.agentscope.core.tool.Toolkit;
import io.pigagent.core.memory.ConversationMemory;
import io.pigagent.core.memory.EphemeralMemoryContextHook;
import reactor.core.publisher.Flux;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * The central Pig Agent, wrapping AgentScope's ReActAgent.
 * Delegates to the underlying ReActAgent for reasoning and tool calling.
 *
 * <p><b>AgentScope 2.0 migration notes (av2 Phase 0):</b>
 * <ul>
 *   <li>The 1.x {@code .memory(Memory)} builder is gone — conversation state now lives on
 *       {@code AgentState.getContext()}, persisted via an {@link AgentStateStore}
 *       (default {@link InMemoryAgentStateStore}).</li>
 *   <li>{@code ReActAgent} is stateless; a call/stream is keyed by {@code (userId, sessionId)} via a
 *       {@link RuntimeContext}. Phase 0 uses the default session ({@link RuntimeContext#empty()} +
 *       no-arg {@code getAgentState()}); wiring a per-session {@code RuntimeContext} is Phase-1
 *       (session module) work.</li>
 *   <li>{@code call(Msg)}/{@code stream(Msg)} single-arg overloads were removed. {@link #call(Msg)}
 *       now calls {@code call(List, RuntimeContext)}; {@link #stream(Msg)} moves to
 *       {@code streamEvents(Msg)} returning {@code Flux<AgentEvent>} (the deprecated
 *       {@code Flux<io.agentscope.core.agent.Event>} stream is retired).</li>
 *   <li>Long-term memory is still injected on the user side, ephemerally, via our own hook — NOT
 *       through AgentScope wiring (see {@link EphemeralMemoryContextHook}). The forward path for
 *       this hook is a {@code MiddlewareBase#onReasoning} (deferred to Phase 1).</li>
 * </ul>
 */
public final class PigAgent {

    /** State-store partition for this single-user terminal app. */
    private static final String USER_ID = "pig";

    private final ReActAgent reactAgent;
    private final String agentName;
    private final Model model;
    private final AgentStateStore stateStore;

    private PigAgent(ReActAgent reactAgent, String agentName, Model model, AgentStateStore stateStore) {
        this.reactAgent = Objects.requireNonNull(reactAgent, "reactAgent");
        this.agentName = Objects.requireNonNull(agentName, "agentName");
        this.model = Objects.requireNonNull(model, "model");
        this.stateStore = Objects.requireNonNull(stateStore, "stateStore");
    }

    public static Builder builder() {
        return new Builder();
    }

    public Msg call(Msg userMsg) {
        return reactAgent.call(List.of(userMsg), RuntimeContext.empty()).block();
    }

    public Flux<AgentEvent> stream(Msg userMsg) {
        return reactAgent.streamEvents(userMsg);
    }

    public String getAgentName() {
        return agentName;
    }

    public Model getModel() {
        return model;
    }

    public ReActAgent getReactAgent() {
        return reactAgent;
    }

    /**
     * The agent's short-term conversation, adapted as a {@link Memory} view over the 2.0
     * {@code AgentState} context (used to inspect, clear, or rewrite history, e.g. by compression).
     */
    public Memory getMemory() {
        return new ConversationMemory(reactAgent);
    }

    /** Clear the current conversation history. */
    public void clearMemory() {
        reactAgent.getAgentState().contextMutable().clear();
    }

    /** Persist the agent's state (incl. conversation) under the given session id. */
    public void saveTo(String sessionId) {
        reactAgent.saveAgentState(USER_ID, sessionId);
    }

    /**
     * Whether persisted state exists for the given session id. Actual restoration is automatic on
     * the next {@code call}/{@code stream} that carries a session-bound {@link RuntimeContext}
     * (Phase-1 session wiring).
     */
    public boolean loadIfExists(String sessionId) {
        return stateStore.exists(USER_ID, sessionId);
    }

    public static final class Builder {
        private String name = "PigAgent";
        private String sysPrompt = "You are a helpful AI assistant.";
        private Model model;
        private Toolkit toolkit;
        private List<Hook> hooks;
        private LongTermMemory longTermMemory;
        private AgentStateStore stateStore;
        private int maxIters; // 0 = do not set (keep AgentScope's default)

        private Builder() {}

        public Builder name(String name) {
            this.name = name;
            return this;
        }

        public Builder sysPrompt(String sysPrompt) {
            this.sysPrompt = sysPrompt;
            return this;
        }

        public Builder model(Model model) {
            this.model = model;
            return this;
        }

        public Builder toolkit(Toolkit toolkit) {
            this.toolkit = toolkit;
            return this;
        }

        public Builder hooks(List<Hook> hooks) {
            this.hooks = hooks;
            return this;
        }

        public Builder longTermMemory(LongTermMemory longTermMemory) {
            this.longTermMemory = longTermMemory;
            return this;
        }

        /** Override the state store (default {@link InMemoryAgentStateStore}). */
        public Builder stateStore(AgentStateStore stateStore) {
            this.stateStore = stateStore;
            return this;
        }

        /**
         * Bound the reasoning-tool loop of the underlying {@code ReActAgent}. Only applied when
         * {@code > 0}; {@code <= 0} leaves AgentScope's own default in place. Guards autonomous and
         * interactive agents alike against unbounded tool-call ping-pong.
         */
        public Builder maxIters(int maxIters) {
            this.maxIters = maxIters;
            return this;
        }

        public PigAgent build() {
            Objects.requireNonNull(model, "model must be set before building");

            AgentStateStore effectiveStore =
                    stateStore != null ? stateStore : new InMemoryAgentStateStore();

            ReActAgent.Builder reactBuilder = ReActAgent.builder()
                    .name(name)
                    .sysPrompt(sysPrompt)
                    .model(model)
                    .stateStore(effectiveStore);

            if (maxIters > 0) {
                reactBuilder.maxIters(maxIters);
            }

            // Long-term memory is injected on the user side, ephemerally, via our own hook — NOT
            // through AgentScope's long-term-memory wiring, whose injection is persisted into the
            // conversation and accumulates every turn. See EphemeralMemoryContextHook.
            List<Hook> effectiveHooks = new ArrayList<>();
            if (hooks != null) {
                effectiveHooks.addAll(hooks);
            }
            if (longTermMemory != null) {
                effectiveHooks.add(new EphemeralMemoryContextHook(longTermMemory));
            }

            if (toolkit != null) {
                reactBuilder.toolkit(toolkit);
            }
            if (!effectiveHooks.isEmpty()) {
                reactBuilder.hooks(effectiveHooks);
            }

            ReActAgent reactAgent = reactBuilder.build();
            return new PigAgent(reactAgent, name, model, effectiveStore);
        }
    }
}
