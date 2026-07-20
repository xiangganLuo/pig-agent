package io.pigagent.core.agent;

import io.agentscope.core.ReActAgent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.memory.Memory;
import io.agentscope.core.message.Msg;
import io.agentscope.core.middleware.MiddlewareBase;
import io.agentscope.core.model.Model;
import io.agentscope.core.permission.PermissionBehavior;
import io.agentscope.core.permission.PermissionContextState;
import io.agentscope.core.permission.PermissionMode;
import io.agentscope.core.permission.PermissionRule;
import io.agentscope.core.state.AgentState;
import io.agentscope.core.state.AgentStateStore;
import io.agentscope.core.state.InMemoryAgentStateStore;
import io.agentscope.core.tool.Toolkit;
import io.agentscope.harness.agent.HarnessAgent;
import io.agentscope.harness.agent.memory.MemoryConfig;
import io.agentscope.harness.agent.memory.compaction.ToolResultEvictionConfig;
import io.agentscope.harness.agent.subagent.SubagentDeclaration;
import io.pigagent.core.memory.ConversationMemory;
import io.pigagent.core.memory.NativeMemoryContextMiddleware;
import io.pigagent.core.memory.injection.MemoryInjection;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Flux;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

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
 *   <li>Long-term memory is the AgentScope 2.0 <b>native two-layer memory</b> ({@code pa-memory-native}):
 *       native flush/consolidation hooks + memory tools write the workspace-level {@code MEMORY.md}, and
 *       pig injects it into the system prompt via {@code NativeMemoryContextMiddleware}
 *       ({@link Builder#memory(MemoryConfig)}).</li>
 * </ul>
 *
 * <p><b>av2 Phase 5b — HarnessAgent adoption (the vehicle).</b> {@code PigAgent} now wraps a
 * {@link HarnessAgent} instead of a bare {@code ReActAgent}. The {@code HarnessAgent} is a thin
 * delegating vehicle around the exact same {@code ReActAgent} pig builds (its
 * {@link HarnessAgent#getDelegate()} is that {@code ReActAgent}), carrying pig's {@code Toolkit},
 * middlewares (native-memory injection / loop-detection / logging), native {@code stateStore},
 * {@code permissionContext}, {@code maxRetries}/{@code fallbackModel} and {@code maxIters}
 * <em>unchanged</em>. Turn methods ({@code call}/{@code stream}/{@code streamEvents}) run through the
 * {@code HarnessAgent} so its native <b>tool-result eviction</b> (the one gap pig lacked) applies;
 * conversation-state operations (per-session {@code getAgentState}/{@code saveAgentState}, interrupt,
 * {@link ConversationMemory}) run through {@code getDelegate()} — the exact instance the vehicle uses,
 * so state stays consistent. Every batteries-included harness extra pig already owns is disabled at
 * build ({@code disableFilesystemTools}/{@code disableShellTool} — pig's guarded FileSystemTools/
 * ShellTools; {@code disableCompaction} — pig's A5 context-engineering; native <b>subagents</b> are
 * enabled on demand (av2 Phase 6a — {@link Builder#subagents(boolean)}; disabled by default so a bare
 * builder is byte-for-byte the old behavior);
 * {@code disableWorkspaceContext}/{@code disableAtPathExpansion}/{@code disableDynamicSkills}/
 * {@code disableToolsConfig} — pig owns the system prompt + toolkit; {@code disableSessionPersistence}
 * — pig's {@code AgentStateStore} stays the single persistence mechanism). <b>Native two-layer
 * long-term memory</b> ({@code pa-memory-native}) is enabled via {@link Builder#memory(MemoryConfig)}
 * (flush/consolidation hooks + memory tools ON + {@code NativeMemoryContextMiddleware} injection); when
 * no config is supplied it stays disabled ({@code disableMemoryTools}/{@code disableMemoryHooks}). Only
 * tool-result eviction is turned on unconditionally. The public method surface is unchanged, so
 * cli/web/channel/kernel are untouched.
 */
public final class PigAgent {

    private static final Logger log = LoggerFactory.getLogger(PigAgent.class);

    /** State-store partition for this single-user terminal app (the pig {@code userId}). */
    private static final String USER_ID = "pig";

    /** The built-in general-purpose subagent id (native {@code agent_spawn} target). */
    private static final String GENERAL_PURPOSE_ID = "general-purpose";

    /** The pig hybrid memory-search tool name (hybrid-memory-search); its presence supersedes native memory tools. */
    private static final String PIG_MEMORY_SEARCH_TOOL = "memory_search";

    /** Lazily-created shared temp workspace used only when no workspace is supplied (tests). */
    private static volatile Path fallbackWorkspace;

    private final HarnessAgent harness;
    private final ReActAgent reactAgent;
    private final String agentName;
    private final Model model;
    private final AgentStateStore stateStore;

    private PigAgent(HarnessAgent harness, ReActAgent reactAgent, String agentName,
                     Model model, AgentStateStore stateStore) {
        this.harness = Objects.requireNonNull(harness, "harness");
        this.reactAgent = Objects.requireNonNull(reactAgent, "reactAgent");
        this.agentName = Objects.requireNonNull(agentName, "agentName");
        this.model = Objects.requireNonNull(model, "model");
        this.stateStore = Objects.requireNonNull(stateStore, "stateStore");
    }

    public static Builder builder() {
        return new Builder();
    }

    public Msg call(Msg userMsg) {
        return harness.call(List.of(userMsg), RuntimeContext.empty()).block();
    }

    public Flux<AgentEvent> stream(Msg userMsg) {
        return harness.streamEvents(userMsg);
    }

    /**
     * Session-aware chat: bind the turn to the {@code (userId="pig", sessionId)} slot so the
     * conversation is loaded from / saved to that session's own {@link AgentState} automatically
     * (2.0 native per-{@code (userId,sessionId)} persistence). A {@code null}/blank {@code sessionId}
     * falls back to the default session ({@link #call(Msg)}), so the default-session path keeps
     * working unchanged.
     */
    public Msg call(Msg userMsg, String sessionId) {
        return harness.call(List.of(userMsg), contextFor(sessionId)).block();
    }

    /** Session-aware streaming counterpart of {@link #call(Msg, String)}. */
    public Flux<AgentEvent> stream(Msg userMsg, String sessionId) {
        return harness.streamEvents(userMsg, contextFor(sessionId));
    }

    /**
     * Stream a turn directly to a subagent that was previously exposed via {@code
     * agent_spawn(expose_to_user=true)} (subagent-online-switch). The message is routed through this
     * agent's native gateway to the exposed subagent's own session — <em>bypassing</em> this parent's
     * conversation — via {@code HarnessGateway.runSubagentStream}. The {@code subagentId} is the value
     * carried on the {@code SubagentExposedEvent} the parent turn emitted.
     *
     * <p>Only meaningful when this agent was built with native subagents enabled ({@link
     * Builder#subagents(boolean)}) — the build then eagerly initializes the gateway so the exposure
     * bridge is wired. An unknown/expired {@code subagentId} yields an error {@link Flux} (surfaced as a
     * one-line error by the frontend), never an exception; a {@code null}/blank id is rejected the same
     * way. Streaming (not blocking) so the frontend renders it exactly like a normal turn.
     */
    public Flux<AgentEvent> streamSubagent(String subagentId, Msg userMsg) {
        if (subagentId == null || subagentId.isBlank()) {
            return Flux.error(new IllegalArgumentException("subagentId is required"));
        }
        return harness.gateway().runSubagentStream(subagentId, List.of(userMsg));
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

    /**
     * The underlying {@code ReActAgent} — the {@link HarnessAgent}'s delegate (the exact instance the
     * vehicle runs turns on). This is the seam {@code CompressionService} / {@code AgentRunner} /
     * {@link ConversationMemory} operate on (conversation state), and what the maxIters/retry unit
     * tests inspect. Operating on it is consistent with running turns via the {@code HarnessAgent},
     * because the vehicle delegates to this very instance.
     */
    public ReActAgent getReactAgent() {
        return reactAgent;
    }

    /** The HarnessAgent vehicle wrapping the {@link #getReactAgent() delegate} (av2 Phase 5b). */
    public HarnessAgent getHarnessAgent() {
        return harness;
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
     * Delete the persisted conversation slot ({@code (userId="pig", sessionId)}) from the underlying
     * {@link AgentStateStore} — backs {@code /session delete}, which otherwise removes only the
     * metadata sidecar and orphans the native state on disk (a privacy/disk leak). Fault-tolerant: a
     * {@code null}/blank {@code sessionId} is a no-op and any store failure is logged and swallowed so
     * a multi-session delete loop keeps going. Does not touch the default slot.
     */
    public void deleteConversation(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) {
            return;
        }
        try {
            stateStore.delete(USER_ID, sessionId);
        } catch (Exception e) {
            log.warn("Failed to delete conversation state for session {}: {}", sessionId, e.getMessage());
        }
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
     * Interrupt the in-flight turn on the given session's state slot (av2 Phase 5a). Native
     * replacement for the deleted {@code InterruptibleModel} decorator: triggers the session's
     * {@code InterruptControl} so the ReAct loop aborts at the next cooperative check and writes no
     * half-finished result. A {@code null}/blank {@code sessionId} targets the default slot. The
     * kernel's {@code InterruptController}/{@code TurnHandle} owns the "which turn is in flight"
     * abstraction and additionally terminates the frontend-facing event stream immediately.
     */
    public void interrupt(String sessionId) {
        reactAgent.interrupt(contextFor(sessionId));
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
        harness.setPermissionMode(contextFor(sessionId), nativeMode);
    }

    /** {@code source} tag for the ALLOW rule an "always allow" (user pick {@code a}) writes. */
    private static final String ALWAYS_ALLOW_SOURCE = "user:always";

    /**
     * Persist an "always allow" (user picked {@code a} at an ASK prompt) for one tool on one session,
     * effective from the <em>next</em> turn (change {@code permission-always-allow-persist}).
     *
     * <p><b>Why this is needed.</b> Native {@code applyConfirmResults} adds an accepted ALLOW rule only
     * to the in-flight invocation's engine — it never writes back to {@code AgentState.permissionContext}
     * — and pig runs each user turn as a separate {@code stream()} that reconstructs the engine from the
     * persisted slot context, so the choice is lost next turn.
     *
     * <p><b>Mechanism (session-scoped ASK→ALLOW swap).</b> On the session slot's persisted context we
     * <em>remove that one tool's ASK rule and add an ALLOW rule</em> ({@code deny>ask>allow}: a retained
     * ASK rule would shadow the ALLOW, so both halves are required). Every other tool's ASK rule is left
     * intact (their first-ask is preserved) and deny rules are copied verbatim (never relaxed). We then
     * refresh the per-slot permission-engine cache via {@link ReActAgent#setPermissionMode} (same mode —
     * it does the {@code permissionEngineCache.put(...)} and persists the slot). Scope is this one
     * session; cross-session / cross-restart persistence is the caller's config-allowlist concern.
     * Idempotent: repeated calls yield exactly one {@code user:always} ALLOW and no ASK for the tool.
     * Fault-tolerant: any failure is logged and swallowed (never breaks the turn).
     *
     * @param sessionId the pig session id (the current REPL session)
     * @param toolName  the tool the user chose to always allow
     */
    public void allowToolForSession(String sessionId, String toolName) {
        if (toolName == null || toolName.isBlank()) {
            return;
        }
        try {
            AgentState state = reactAgent.getAgentState(USER_ID, sessionId);
            PermissionContextState updated = withToolAlwaysAllowed(state.getPermissionContext(), toolName);
            state.setPermissionContext(updated);
            // Refresh the per-slot engine cache (ReActAgent.permissionEngineCache is computeIfAbsent) and
            // persist: setPermissionMode(sameMode) re-reads the just-set context (withMode preserves rules),
            // does permissionEngineCache.put(...) and saveAgentState — so the NEXT turn honors the ALLOW.
            reactAgent.setPermissionMode(USER_ID, sessionId, updated.getMode());
        } catch (RuntimeException e) {
            log.warn("Failed to persist always-allow for tool '{}' on session '{}': {}",
                    toolName, sessionId, e.getMessage());
        }
    }

    /**
     * Rebuild {@code cur} with {@code toolName} switched from ASK to ALLOW (see
     * {@link #allowToolForSession}). Copies mode + working dirs + allow/deny rules verbatim, copies all
     * ASK rules <em>except</em> {@code toolName}'s, drops any prior {@code user:always} ALLOW for the tool
     * (idempotency — no stacking), then adds exactly one {@code user:always} ALLOW for it. A {@code null}
     * {@code cur} yields a minimal {@code DEFAULT} context carrying just the ALLOW.
     */
    private static PermissionContextState withToolAlwaysAllowed(PermissionContextState cur, String toolName) {
        PermissionContextState.Builder b = PermissionContextState.builder()
                .mode(cur == null ? PermissionMode.DEFAULT : cur.getMode());
        if (cur != null) {
            cur.getWorkingDirectories().forEach(b::addWorkingDirectory);
            cur.getAllowRules().forEach((n, rs) -> rs.forEach(r -> {
                if (n.equals(toolName) && ALWAYS_ALLOW_SOURCE.equals(r.source())) {
                    return; // drop a prior user:always ALLOW so repeats don't stack (idempotent)
                }
                b.addAllowRule(n, r);
            }));
            cur.getDenyRules().forEach((n, rs) -> rs.forEach(r -> b.addDenyRule(n, r)));
            cur.getAskRules().forEach((n, rs) -> {
                if (!n.equals(toolName)) { // remove ONLY this tool's ASK rule (others keep their first-ask)
                    rs.forEach(r -> b.addAskRule(n, r));
                }
            });
        }
        b.addAllowRule(toolName, new PermissionRule(toolName, null, PermissionBehavior.ALLOW, ALWAYS_ALLOW_SOURCE));
        return b.build();
    }

    /**
     * Enter native Plan Mode for a session's state slot (av2). Backs {@code /plan enter} and is the
     * programmatic equivalent of the model calling {@code plan_enter}: the agent switches to a
     * read-only plan phase (the native {@code PlanModeMiddleware} denies every non-read-only tool by
     * {@code AgentTool.isReadOnly()}) until it exits. A {@code null}/blank {@code sessionId} targets the
     * default slot. Requires the agent to have been built with Plan Mode enabled
     * ({@link Builder#planMode(PlanModeSettings)} with {@code enabled=true}); otherwise the underlying
     * {@code HarnessAgent} has no plan manager and this throws — the CLI gates the call on config first.
     */
    public void enterPlanMode(String sessionId) {
        harness.enterPlanMode(contextFor(sessionId));
    }

    /**
     * Exit native Plan Mode for a session's state slot (av2). Backs the operator-driven {@code /plan
     * exit}: since the operator IS the human approver, a programmatic exit does NOT trigger HITL (that
     * gate is reserved for the model's own {@code plan_exit} tool). After exit the agent may execute
     * mutating tools again (subject to the active permission mode). {@code null}/blank targets default.
     */
    public void exitPlanMode(String sessionId) {
        harness.exitPlanMode(contextFor(sessionId));
    }

    /** Whether native Plan Mode is active for the session's state slot (av2). {@code null}/blank = default. */
    public boolean isPlanModeActive(String sessionId) {
        return harness.isPlanModeActive(contextFor(sessionId));
    }

    /**
     * Release the {@link HarnessAgent} vehicle's resources (it is {@code AutoCloseable}). Safe to call
     * more than once; failures are logged and swallowed. Rebuild-driven lifecycle (model switch / MCP
     * change) does not auto-close superseded agents today — kept out of scope, as the {@code ReActAgent}
     * path never did either; callers/tests that build throwaway agents may call this.
     */
    public void close() {
        try {
            harness.close();
        } catch (Exception e) {
            log.debug("HarnessAgent close failed (ignored): {}", e.getMessage());
        }
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
        private List<MiddlewareBase> middlewares;
        private MemoryConfig memoryConfig; // null = native long-term memory disabled (today's behavior)
        private MemoryInjection memoryInjection; // null = whole-file MEMORY.md injection (today's behavior)
        private AgentStateStore stateStore;
        private PermissionContextState permissionContext;
        private int maxIters; // 0 = do not set (keep AgentScope's default)
        private int maxRetries; // <= 0 = do not set (keep AgentScope's ExecutionConfig default)
        private Model fallbackModel; // nullable
        private Path workspace; // nullable → a shared temp workspace (eviction spool root; tests)
        private ToolResultEvictionConfig toolResultEviction; // null = eviction disabled
        private boolean subagentsEnabled; // false = native subagents disabled (today's behavior)
        private List<SubagentDeclaration> subagentDeclarations; // null/empty = built-in + workspace only
        private PlanModeSettings planMode = PlanModeSettings.disabled(); // disabled = today's behavior
        // deferred-tools: broadcaster so a tool_search reveal fired from a subagent child (a Toolkit.copy)
        // activates the group on that child too. null = not registered (today's behavior; no subagents or
        // deferred tools off).
        private io.pigagent.core.tool.RevealTargets revealTargets;

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

        /**
         * The native {@link MiddlewareBase}s installed on the underlying {@code ReActAgent}
         * (av2 Phase 5a — the 2.0 replacement for the removed {@code hooks(List&lt;Hook&gt;)}). Loop
         * detection + logging come in here; when native long-term memory is enabled
         * ({@link #memory(MemoryConfig)}), the {@code NativeMemoryContextMiddleware} (MEMORY.md →
         * system prompt) is appended last automatically. List order is onion order (first = outermost).
         */
        public Builder middlewares(List<MiddlewareBase> middlewares) {
            this.middlewares = middlewares;
            return this;
        }

        /**
         * Enable AgentScope 2.0 <b>native two-layer long-term memory</b> ({@code pa-memory-native}).
         * When non-null, the {@link HarnessAgent} vehicle keeps its native memory hooks
         * ({@code MemoryFlushMiddleware} flush → {@code memory/YYYY-MM-DD.md}, {@code
         * MemoryMaintenanceMiddleware} consolidation → workspace-level {@code MEMORY.md}) and memory
         * tools ({@code memory_search}/{@code memory_get}/{@code memory_save}/{@code session_search})
         * turned ON, and pig injects the consolidated {@code MEMORY.md} into the system prompt via a
         * {@code NativeMemoryContextMiddleware}. The config's {@code model()} (Doubao lite) runs
         * flush/consolidation off the primary reasoning model (OD8). {@code null} (the default) keeps
         * the native memory hooks/tools DISABLED — byte-for-byte the pre-{@code pa-memory-native}
         * behaviour (used by the connectivity probe, leaf subagents, and {@code /memory off}).
         */
        public Builder memory(MemoryConfig memoryConfig) {
            this.memoryConfig = memoryConfig;
            return this;
        }

        /**
         * Enable RAG-style memory injection ({@code memory-retrieval-injection}). Only takes effect when
         * native long-term memory is also enabled ({@link #memory(MemoryConfig)} non-null), since it
         * refines how {@code MEMORY.md} is surfaced. When non-null <em>and</em> {@code settings.enabled()},
         * the {@code NativeMemoryContextMiddleware} injects only a stable pinned core into the system
         * prompt and injects query-aware top-K facts as a trailing ephemeral message. {@code null} (the
         * default) keeps the whole-{@code MEMORY.md}-into-system-prompt behavior — byte-identical to
         * before this capability.
         */
        public Builder memoryInjection(MemoryInjection memoryInjection) {
            this.memoryInjection = memoryInjection;
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

        /**
         * Native model-call retry (av2 Phase 5a) — replaces the deleted {@code RetryingModel}
         * decorator. Only applied when {@code > 0}; maps to {@code ReActAgent.Builder.maxRetries(int)}
         * (the underlying {@code ExecutionConfig} whose retry filter already distinguishes transient
         * 429/5xx/timeout/IO from permanent 4xx/auth). Pass {@code 1} to effectively disable retry
         * (a single attempt); {@code <= 0} leaves AgentScope's default.
         */
        public Builder maxRetries(int maxRetries) {
            this.maxRetries = maxRetries;
            return this;
        }

        /**
         * Native fallback model tried after the primary model exhausts retries (av2 Phase 5a) — maps
         * to {@code ReActAgent.Builder.fallbackModel(Model)}. {@code null} leaves no fallback.
         */
        public Builder fallbackModel(Model fallbackModel) {
            this.fallbackModel = fallbackModel;
            return this;
        }

        /**
         * The workspace root the {@link HarnessAgent} vehicle uses — chiefly the on-disk root the
         * native tool-result-eviction spools large results into. In production this is pig's workspace
         * root; when {@code null} (unit tests) a shared temp directory is used so no repo/build-tree
         * pollution occurs. Native workspace tools/context are disabled regardless, so nothing here
         * touches pig's {@code AGENT.md}/toolkit or the (byte-stable) system prompt.
         */
        public Builder workspace(Path workspace) {
            this.workspace = workspace;
            return this;
        }

        /**
         * Native tool-result eviction config (av2 Phase 5b) — the one capability pig lacked. When
         * non-null, results larger than {@code config.getMaxResultChars()} spool to disk under the
         * workspace with a read-back placeholder in the context. {@code null} disables eviction. The
         * product default (ON, ~80K) is decided by the wiring layer ({@code AgentBootstrap} builds a
         * config from {@code tools.result-eviction}); a bare builder (tests) defaults to disabled.
         */
        public Builder toolResultEviction(ToolResultEvictionConfig toolResultEviction) {
            this.toolResultEviction = toolResultEviction;
            return this;
        }

        /**
         * Enable native subagent delegation on the {@link HarnessAgent} vehicle (av2 Phase 6a). When
         * {@code true} the vehicle registers the built-in {@code general-purpose} subagent + the
         * {@code agent_spawn}/{@code agent_send}/{@code agent_list}/{@code task_output}/{@code task_cancel}/
         * {@code task_list} orchestration tools and discovers project subagents from
         * {@code <workspace>/subagents/<id>.md} (independent of {@code disableWorkspaceContext}, which
         * only gates persona/context injection). When {@code false} (the default) the vehicle keeps
         * calling {@code disableSubagents()}/{@code disableDynamicSubagents()} — <em>exactly</em> the
         * pre-6a behavior (no subagent tools in the schema). Subagent delegation is orthogonal to pig's
         * <em>peer</em> agents ({@code AgentRegistry} + {@code /agent use}): peers switch the active
         * agent; subagents are transient children the active agent delegates a subtask to.
         */
        public Builder subagents(boolean enabled) {
            this.subagentsEnabled = enabled;
            return this;
        }

        /**
         * Code-declared subagents to surface on the vehicle in addition to the built-in
         * {@code general-purpose} and any {@code <workspace>/subagents/*.md} (av2 Phase 6a). Only applied
         * when {@link #subagents(boolean)} is {@code true}. pig maps its declared peer {@code AgentSpec}s
         * to these via {@code AgentSpecSubagentMapper}, so a pig-declared agent can be <em>both</em> a
         * switchable peer and a delegable subagent. {@code null}/empty = built-in + workspace only.
         */
        public Builder subagentDeclarations(List<SubagentDeclaration> subagentDeclarations) {
            this.subagentDeclarations = subagentDeclarations;
            return this;
        }

        /**
         * Register this agent's spawned subagent child toolkits (each a {@code Toolkit.copy()}) into a
         * shared {@link io.pigagent.core.tool.RevealTargets} so a {@code tool_search} reveal fired during a
         * child's turn activates the tool group on that child's own (independent-group-state) copy —
         * fixing the deferred-tools "reveal does not propagate across copy()" limitation. {@code null}
         * (default) = children are not registered (today's behavior). Only meaningful with subagents on.
         */
        public Builder revealTargets(io.pigagent.core.tool.RevealTargets revealTargets) {
            this.revealTargets = revealTargets;
            return this;
        }

        /**
         * Native Plan Mode settings for this agent (av2). When {@code settings.enabled()} the
         * {@link HarnessAgent} vehicle installs the plan trio ({@code plan_enter}/{@code plan_write}/
         * {@code plan_exit}) + the {@code PlanModeMiddleware} read-only enforcer, writing plans under
         * {@code settings.planDir()} (workspace-relative). {@code null} or {@link PlanModeSettings#disabled()}
         * leaves Plan Mode off — no plan tools, byte-stable system prompt, exactly today's behavior.
         * Spawned leaf subagents deliberately do NOT get Plan Mode (they cannot be spawned while the
         * parent is plan-active anyway — {@code agent_spawn} is not read-only → denied — and have no
         * confirmer for a {@code plan_exit} HITL).
         */
        public Builder planMode(PlanModeSettings settings) {
            this.planMode = settings == null ? PlanModeSettings.disabled() : settings;
            return this;
        }

        public PigAgent build() {
            Objects.requireNonNull(model, "model must be set before building");

            AgentStateStore effectiveStore =
                    stateStore != null ? stateStore : new InMemoryAgentStateStore();
            Path resolvedWorkspace = workspace != null ? workspace : fallbackWorkspace();

            // Middleware chain (loop-detection/logging first). When native long-term memory is enabled
            // (memoryConfig != null, pa-memory-native), pig injects the consolidated MEMORY.md into the
            // system prompt via NativeMemoryContextMiddleware — appended LAST (its onSystemPrompt runs
            // once per call; every other stage is identity). The native flush/consolidation hooks +
            // memory tools do the WRITING; this only surfaces MEMORY.md to the model.
            List<MiddlewareBase> effectiveMiddlewares = new ArrayList<>();
            if (middlewares != null) {
                effectiveMiddlewares.addAll(middlewares);
            }
            boolean memoryEnabled = memoryConfig != null;
            if (memoryEnabled) {
                Path memoryFile = resolvedWorkspace.resolve("MEMORY.md");
                if (memoryInjection != null && memoryInjection.settings().enabled()) {
                    // memory-retrieval-injection: pinned core → system prompt, query-aware → ephemeral.
                    effectiveMiddlewares.add(new NativeMemoryContextMiddleware(
                            memoryFile, memoryInjection.settings(), memoryInjection.retriever()));
                } else {
                    effectiveMiddlewares.add(new NativeMemoryContextMiddleware(memoryFile));
                }
            }

            // av2 Phase 5b: build a HarnessAgent VEHICLE around the same ReActAgent config. The
            // HarnessAgent.Builder setters delegate to an inner ReActAgent.Builder (toolkit/middlewares/
            // stateStore/permissionContext/maxRetries/fallbackModel/maxIters), so pig's config reaches
            // the delegate unchanged (delegate == getDelegate()). We disable every batteries-included
            // extra pig already owns and turn ON tool-result eviction (the gap pig lacked) + native
            // long-term memory when configured (pa-memory-native).
            HarnessAgent.Builder hb = HarnessAgent.builder()
                    .name(name)
                    .sysPrompt(sysPrompt)
                    .model(model)
                    .stateStore(effectiveStore)
                    .toolkit(toolkit) // setter tolerates null (creates an empty Toolkit)
                    .workspace(resolvedWorkspace)
                    // pig owns these — disable the native equivalents so there is no overlap:
                    .disableFilesystemTools()   // pig's guarded FileSystemTools
                    .disableShellTool()         // pig's ShellTools + command sandbox
                    .disableCompaction()        // pig's A5 context-engineering compaction
                    .disableWorkspaceContext()  // pig assembles its own byte-stable system prompt
                                                // (native MEMORY.md injected by NativeMemoryContextMiddleware)
                    .disableAtPathExpansion()
                    .disableDynamicSkills()     // pig's SkillsTool + built-in skills
                    .disableDefaultWorkspaceSkills()
                    .disableToolsConfig()       // pig manages its own Toolkit (no tools.json)
                    .disableSessionPersistence(); // pig's AgentStateStore is the single mechanism

            // pa-memory-native: native two-layer long-term memory (flush + consolidation + memory
            // tools). When enabled, keep the native memory hooks/tools ON via .memory(config);
            // otherwise disable them (byte-for-byte the pre-feature behaviour).
            if (memoryEnabled) {
                hb.memory(memoryConfig);
                // hybrid-memory-search: when pig registered its own hybrid `memory_search` into the
                // toolkit (memory.search.hybrid-enabled), suppress the native memory TOOLS so the two
                // same-named tools never collide — pig's hybrid tool supersedes the native keyword scan.
                // The native flush/consolidation HOOKS stay ON (MEMORY.md is still written); only the
                // native memory tools (incl. memory_get/memory_save/session_search) are dropped — a
                // documented, off-by-default trade-off of the hybrid search path.
                if (toolkit != null && toolkit.getToolNames().contains(PIG_MEMORY_SEARCH_TOOL)) {
                    hb.disableMemoryTools();
                }
            } else {
                hb.disableMemoryTools().disableMemoryHooks();
            }

            if (maxIters > 0) {
                hb.maxIters(maxIters);
            }
            if (maxRetries > 0) {
                hb.maxRetries(maxRetries);
            }
            if (fallbackModel != null) {
                hb.fallbackModel(fallbackModel);
            }
            if (permissionContext != null) {
                hb.permissionContext(permissionContext);
            }
            if (!effectiveMiddlewares.isEmpty()) {
                hb.middlewares(effectiveMiddlewares);
            }
            if (toolResultEviction != null) {
                hb.toolResultEviction(toolResultEviction);
            } else {
                hb.disableToolResultEviction();
            }

            // av2 native Plan Mode (config-gated): install the plan trio + PlanModeMiddleware read-only
            // enforcer on the vehicle. The harness copies the toolkit and registers plan_enter/plan_write/
            // plan_exit into that copy before building the ReActAgent, and enforces the plan-phase
            // read-only guarantee via toolkit.getTool(name).isReadOnly() — which pig's tools already
            // report correctly (GuardedAgentTool snapshots the delegate's readOnly flag). Disabled (the
            // default) → no plan tools, byte-stable system prompt, exactly today's behavior.
            if (planMode.enabled()) {
                hb.enablePlanMode()
                        .planFileDirectory(planMode.planDir())
                        .allowShellInPlanMode(planMode.allowShell());
            }

            // av2 Phase 6a/6b: native subagent delegation WITH pig-enforced permission inheritance.
            // Enabled → the vehicle registers the built-in general-purpose subagent + agent_spawn/send/
            // list + task_* tools and discovers <workspace>/subagents/*.md; any code-declared subagents
            // (pig peer specs) are added too. Disabled (default until 6b) → keep the pre-6a disable calls
            // so the schema is byte-for-byte the old one. The 3-level recursion cap (leaf children) IS a
            // native invariant.
            //
            // Phase-6b inheritance (security): 2.0.0's SubagentDeclaration.inheritParentPermissions is
            // declared but INERT (no harness class reads it) — a natively-spawned child would run under
            // its own permissive context and could execute a tool the parent denied (a permission-escape,
            // which is why 6a kept the default OFF). We close it by building every spawnable child
            // ourselves via HarnessAgent.Builder.subagentFactory(name, fn): the custom factory is appended
            // AFTER the built-in general-purpose + declared entries, so it wins the last-put factory map
            // DefaultAgentManager.createAgent resolves against. Each child is a LEAF (subagents disabled)
            // running under SubagentPermissions.deriveChildContext(parent) — the parent's DENY rules bind
            // it, inherited ASK fail-closes to DENY (a child has no confirmer), EXPLORE/BYPASS are
            // preserved. With the escape closed, config `subagents.enabled` now defaults ON.
            if (subagentsEnabled) {
                registerInheritingSubagentFactories(hb);
                if (subagentDeclarations != null && !subagentDeclarations.isEmpty()) {
                    hb.subagents(subagentDeclarations);
                }
            } else {
                hb.disableSubagents();
                hb.disableDynamicSubagents();
            }

            HarnessAgent harness = hb.build();

            // subagent-online-switch: when native subagents are enabled (interactive + peer tracks
            // only — channel/autonomous keep subagents off), eagerly initialize the HarnessAgent's
            // internal gateway. This is the documented enabler for `expose_to_user`: ensureGateway()
            // wires the SubagentGatewayBridge onto the subagent middleware, so a subagent spawned with
            // expose_to_user=true is registered as a user-addressable session and emits a
            // SubagentExposedEvent (carrying a subagentId) onto this parent's stream — after which the
            // frontend can talk to it directly via streamSubagent(...). Spike-verified (javap + offline
            // PoC): gateway() is a lazy, SEPARATE object; the direct stream/streamEvents path never
            // routes through it, so binding it does NOT regress the normal chat turn (no fair queuing /
            // routing engine on the interactive path). Cheap + no IO (no model call).
            if (subagentsEnabled) {
                try {
                    harness.gateway();
                } catch (RuntimeException e) {
                    // Never let the exposure enabler break agent construction — subagent switch is an
                    // additive convenience; the normal chat/delegation path is unaffected either way.
                    log.warn("Subagent exposure gateway init failed (switch disabled this build): {}",
                            e.getMessage());
                }
            }
            return new PigAgent(harness, harness.getDelegate(), name, model, effectiveStore);
        }

        /**
         * Register a pig-owned {@code subagentFactory} for the built-in {@code general-purpose} child and
         * for every declared subagent, so each spawnable child is built under a fail-closed permission
         * context derived from this parent's (av2 Phase-6b). The factory is invoked per-spawn (so the
         * child toolkit is a fresh {@link Toolkit#copy()} and its state is ephemeral in-memory), and the
         * child is a LEAF ({@code disableSubagents}) so the recursion cap and no-escalation both hold.
         */
        private void registerInheritingSubagentFactories(HarnessAgent.Builder hb) {
            PermissionContextState childCtx = permissionContext == null
                    ? null : SubagentPermissions.deriveChildContext(permissionContext);
            Path childWorkspace = workspace != null ? workspace : fallbackWorkspace();
            Toolkit parentToolkit = toolkit != null ? toolkit : new Toolkit();
            String parentPrompt = sysPrompt;
            Model parentModel = model;
            int childIters = maxIters;
            int childRetries = maxRetries;
            io.pigagent.core.tool.RevealTargets targets = revealTargets;
            hb.subagentFactory(GENERAL_PURPOSE_ID, ignoredName -> {
                Toolkit childTk = registerReveal(parentToolkit.copy(), targets);
                return buildLeafChild(GENERAL_PURPOSE_ID, parentPrompt, childTk, childCtx,
                        parentModel, childWorkspace, childIters, childRetries);
            });
            if (subagentDeclarations != null) {
                for (SubagentDeclaration d : subagentDeclarations) {
                    String childName = d.getName();
                    String body = d.getInlineAgentsBody();
                    String childPrompt = (body != null && !body.isBlank()) ? body : parentPrompt;
                    List<String> tools = d.getTools();
                    hb.subagentFactory(childName, ignoredName -> {
                        Toolkit childTk = registerReveal(childToolkit(parentToolkit, tools), targets);
                        return buildLeafChild(childName, childPrompt, childTk, childCtx,
                                parentModel, childWorkspace, childIters, childRetries);
                    });
                }
            }
        }

        /**
         * Register a freshly-created subagent child toolkit into the reveal broadcaster (so a
         * {@code tool_search} reveal from the child activates the group on this copy too), then return it.
         * No-op when {@code targets} is null (subagents/deferred off) — returns the toolkit unchanged.
         */
        private static Toolkit registerReveal(Toolkit childToolkit, io.pigagent.core.tool.RevealTargets targets) {
            if (targets != null) {
                targets.register(childToolkit);
            }
            return childToolkit;
        }

        /**
         * Build a single leaf child {@link HarnessAgent} for a subagent spawn: the parent's model, a
         * fresh (isolated, ephemeral) toolkit + state store, the derived fail-closed permission context,
         * and every native batteries-included extra disabled (mirroring the parent) — crucially
         * {@code disableSubagents()} so the child cannot spawn (leaf).
         */
        private static io.agentscope.core.agent.Agent buildLeafChild(
                String childName, String childSysPrompt, Toolkit childToolkit,
                PermissionContextState childCtx, Model model, Path workspace,
                int maxIters, int maxRetries) {
            HarnessAgent.Builder cb = HarnessAgent.builder()
                    .name(childName + "-subagent")
                    .sysPrompt(childSysPrompt)
                    .model(model)
                    .stateStore(new InMemoryAgentStateStore())
                    .toolkit(childToolkit)
                    .workspace(workspace)
                    .disableFilesystemTools()
                    .disableShellTool()
                    .disableMemoryTools()
                    .disableMemoryHooks()
                    .disableCompaction()
                    .disableWorkspaceContext()
                    .disableAtPathExpansion()
                    .disableDynamicSkills()
                    .disableDefaultWorkspaceSkills()
                    .disableToolsConfig()
                    .disableSessionPersistence()
                    .disableToolResultEviction()
                    .disableSubagents()        // LEAF — a spawned child cannot itself spawn
                    .disableDynamicSubagents();
            if (childCtx != null) {
                cb.permissionContext(childCtx);
            }
            if (maxIters > 0) {
                cb.maxIters(maxIters);
            }
            if (maxRetries > 0) {
                cb.maxRetries(maxRetries);
            }
            return cb.build();
        }

        /**
         * The child's toolkit: a fresh {@link Toolkit#copy()} of the parent's, narrowed to
         * {@code allowedTools} when a subset is declared (empty/null = inherit all). Filtering by name
         * keeps the child's tools a subset of the parent's, so the child never sees a tool the parent
         * lacks.
         */
        private static Toolkit childToolkit(Toolkit parent, List<String> allowedTools) {
            Toolkit copy = parent.copy();
            if (allowedTools == null || allowedTools.isEmpty()) {
                return copy;
            }
            Set<String> keep = new HashSet<>(allowedTools);
            for (String name : new HashSet<>(copy.getToolNames())) {
                if (!keep.contains(name)) {
                    copy.removeTool(name);
                }
            }
            return copy;
        }
    }

    /**
     * A process-wide temp workspace used only when no workspace is supplied to the builder (unit
     * tests). Created lazily so production (which always supplies pig's workspace) never touches it,
     * and placed under the OS temp dir so it never pollutes the repo/build tree. Failure falls back to
     * a fixed temp path — the native {@code WorkspaceManager} only warns on a missing dir, so a
     * non-existent path is tolerated.
     */
    private static Path fallbackWorkspace() {
        Path ws = fallbackWorkspace;
        if (ws != null) {
            return ws;
        }
        synchronized (PigAgent.class) {
            if (fallbackWorkspace == null) {
                try {
                    fallbackWorkspace = Files.createTempDirectory("pig-agent-ws-");
                } catch (IOException e) {
                    fallbackWorkspace = Path.of(System.getProperty("java.io.tmpdir"), "pig-agent-ws");
                }
            }
            return fallbackWorkspace;
        }
    }
}
