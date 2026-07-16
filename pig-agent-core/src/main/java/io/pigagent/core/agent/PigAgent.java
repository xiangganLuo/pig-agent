package io.pigagent.core.agent;

import io.agentscope.core.ReActAgent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.hook.Hook;
import io.agentscope.core.memory.LongTermMemory;
import io.agentscope.core.memory.Memory;
import io.agentscope.core.message.Msg;
import io.agentscope.core.model.Model;
import io.agentscope.core.permission.PermissionContextState;
import io.agentscope.core.permission.PermissionMode;
import io.agentscope.core.state.AgentState;
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
 * <p><b>AgentScope 2.0 migration notes (av2 Phase 0 + Phase 3):</b>
 * <ul>
 *   <li>The 1.x {@code .memory(Memory)} builder is gone — conversation state now lives on
 *       {@code AgentState.getContext()}, persisted via an {@link AgentStateStore}
 *       (default {@link InMemoryAgentStateStore}; the CLI injects a shared
 *       {@code JsonFileAgentStateStore} so state survives model switches + restarts).</li>
 *   <li>{@code ReActAgent} is stateless; a call/stream is keyed by {@code (userId, sessionId)} via a
 *       {@link RuntimeContext}. The no-arg {@link #call(Msg)}/{@link #stream(Msg)} use the default
 *       session ({@link RuntimeContext#empty()}); the session-aware {@link #call(Msg, String)}/
 *       {@link #stream(Msg, String)} thread a {@code (userId="pig", sessionId)} context so each
 *       pig session persists into its own slot automatically (Phase 3). The native store
 *       auto-loads the slot before a turn and auto-saves it after — no manual load/save per turn.</li>
 *   <li>{@code call(Msg)}/{@code stream(Msg)} single-arg overloads were removed on the ReActAgent.
 *       {@link #call(Msg)} now calls {@code call(List, RuntimeContext)}; {@link #stream(Msg)} moves to
 *       {@code streamEvents(Msg)} returning {@code Flux<AgentEvent>} (the deprecated
 *       {@code Flux<io.agentscope.core.agent.Event>} stream is retired).</li>
 *   <li>Long-term memory is still injected on the user side, ephemerally, via our own hook — NOT
 *       through AgentScope wiring (see {@link EphemeralMemoryContextHook}). The forward path for
 *       this hook is a {@code MiddlewareBase#onReasoning} (deferred to Phase 4).</li>
 * </ul>
 */
public final class PigAgent {

    /** State-store partition for this single-user terminal app (the pig {@code userId}). */
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

    /**
     * Session-aware chat: bind the turn to the {@code (userId="pig", sessionId)} slot so the
     * conversation is loaded from / saved to that session's own {@link AgentState} automatically
     * (2.0 native per-{@code (userId,sessionId)} persistence). A {@code null}/blank {@code sessionId}
     * falls back to the default session ({@link #call(Msg)}), so the default-session path keeps
     * working unchanged.
     */
    public Msg call(Msg userMsg, String sessionId) {
        return reactAgent.call(List.of(userMsg), contextFor(sessionId)).block();
    }

    /** Session-aware streaming counterpart of {@link #call(Msg, String)}. */
    public Flux<AgentEvent> stream(Msg userMsg, String sessionId) {
        return reactAgent.streamEvents(userMsg, contextFor(sessionId));
    }

    /** The per-call runtime context for a pig session id (default session when null/blank). */
    private static RuntimeContext contextFor(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) {
            return RuntimeContext.empty();
        }
        return RuntimeContext.builder().userId(USER_ID).sessionId(sessionId).build();
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
     * This targets the <em>default</em> session; use {@link #getMemory(String)} for a specific one.
     */
    public Memory getMemory() {
        return new ConversationMemory(reactAgent);
    }

    /**
     * A {@link Memory} view over a specific session's conversation slot
     * ({@code (userId="pig", sessionId)}). This is the seam a session-aware compression path uses so
     * it rewrites the right conversation; native compaction adoption is deferred to Phase 4.
     */
    public Memory getMemory(String sessionId) {
        return new ConversationMemory(reactAgent, USER_ID, sessionId);
    }

    /** Clear the default session's conversation history. */
    public void clearMemory() {
        reactAgent.getAgentState().contextMutable().clear();
    }

    /** Clear one session's conversation and persist the emptied slot. */
    public void clearConversation(String sessionId) {
        reactAgent.getAgentState(USER_ID, sessionId).contextMutable().clear();
        reactAgent.saveAgentState(USER_ID, sessionId);
    }

    /**
     * Copy one session's conversation into another session's slot and persist it (backs
     * {@code /session fork}). A no-op when source and target are the same. Only the conversation
     * context is copied; metadata (name/timestamps/lineage) is the sidecar's concern.
     */
    public void copyConversation(String fromSessionId, String toSessionId) {
        if (fromSessionId == null || toSessionId == null || fromSessionId.equals(toSessionId)) {
            return;
        }
        List<Msg> source = reactAgent.getAgentState(USER_ID, fromSessionId).getContext();
        AgentState target = reactAgent.getAgentState(USER_ID, toSessionId);
        target.contextMutable().clear();
        target.contextMutable().addAll(source);
        reactAgent.saveAgentState(USER_ID, toSessionId);
    }

    /** Persist the agent's state (incl. conversation) under the given session id. */
    public void saveTo(String sessionId) {
        reactAgent.saveAgentState(USER_ID, sessionId);
    }

    /**
     * Switch the native permission mode for a session's state slot at runtime (av2 Phase 4). Backs
     * pig's {@code /permission mode}: the base {@link PermissionMode} of the {@code (userId="pig",
     * sessionId)} slot is flipped in place (a {@code null}/blank {@code sessionId} targets the default
     * slot). Existing per-tool rules (built at agent-build time from the mode-at-build) are preserved;
     * the native built-in checks (EXPLORE read-only, BYPASS allow-all, DONT_ASK ask→deny) do the
     * heavy lifting, so switching <em>to</em> plan/bypass is exact and switching to ask/auto is
     * fail-safe (never more permissive than the built rules already allow). A full re-derivation of
     * per-tool rules happens when the agent is rebuilt (model switch) or on a fresh session.
     */
    public void setPermissionMode(PermissionMode nativeMode, String sessionId) {
        reactAgent.setPermissionMode(contextFor(sessionId), nativeMode);
    }

    /**
     * Whether persisted state exists for the given session id. Actual restoration is automatic on
     * the next session-aware {@code call}/{@code stream} (see {@link #stream(Msg, String)}), which
     * carries a session-bound {@link RuntimeContext} the native store resolves.
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
        private PermissionContextState permissionContext;
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
         * The native permission context (mode + per-tool rules) installed on the underlying
         * {@code ReActAgent} — the 2.0 replacement for the deleted {@code ToolPermissionHook}
         * (av2 Phase 4). Built by pig's {@code PermissionContextFactory} in the wiring layer from the
         * active mode + toolkit + interactive flag. {@code null} leaves AgentScope's default context.
         */
        public Builder permissionContext(PermissionContextState permissionContext) {
            this.permissionContext = permissionContext;
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
            if (permissionContext != null) {
                reactBuilder.permissionContext(permissionContext);
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
