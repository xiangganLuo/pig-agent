package io.pigagent.core.agent;

import io.agentscope.core.ReActAgent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.memory.LongTermMemory;
import io.agentscope.core.memory.Memory;
import io.agentscope.core.message.Msg;
import io.agentscope.core.middleware.MiddlewareBase;
import io.agentscope.core.model.Model;
import io.agentscope.core.permission.PermissionContextState;
import io.agentscope.core.permission.PermissionMode;
import io.agentscope.core.state.AgentState;
import io.agentscope.core.state.AgentStateStore;
import io.agentscope.core.state.InMemoryAgentStateStore;
import io.agentscope.core.tool.Toolkit;
import io.agentscope.harness.agent.HarnessAgent;
import io.agentscope.harness.agent.memory.compaction.ToolResultEvictionConfig;
import io.pigagent.core.memory.ConversationMemory;
import io.pigagent.core.memory.EphemeralMemoryMiddleware;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Flux;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
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
 *       through AgentScope wiring (see {@link EphemeralMemoryMiddleware}). The forward path for
 *       this hook is a {@code MiddlewareBase#onReasoning} (deferred to Phase 4).</li>
 * </ul>
 *
 * <p><b>av2 Phase 5b — HarnessAgent adoption (the vehicle).</b> {@code PigAgent} now wraps a
 * {@link HarnessAgent} instead of a bare {@code ReActAgent}. The {@code HarnessAgent} is a thin
 * delegating vehicle around the exact same {@code ReActAgent} pig builds (its
 * {@link HarnessAgent#getDelegate()} is that {@code ReActAgent}), carrying pig's {@code Toolkit},
 * middlewares (ephemeral-memory / loop-detection / logging), native {@code stateStore},
 * {@code permissionContext}, {@code maxRetries}/{@code fallbackModel} and {@code maxIters}
 * <em>unchanged</em>. Turn methods ({@code call}/{@code stream}/{@code streamEvents}) run through the
 * {@code HarnessAgent} so its native <b>tool-result eviction</b> (the one gap pig lacked) applies;
 * conversation-state operations (per-session {@code getAgentState}/{@code saveAgentState}, interrupt,
 * {@link ConversationMemory}) run through {@code getDelegate()} — the exact instance the vehicle uses,
 * so state stays consistent. Every batteries-included harness extra pig already owns is disabled at
 * build ({@code disableFilesystemTools}/{@code disableShellTool} — pig's guarded FileSystemTools/
 * ShellTools; {@code disableMemoryTools}/{@code disableMemoryHooks} — pig's ephemeral + A4;
 * {@code disableCompaction} — pig's A5 context-engineering; {@code disableSubagents} — Phase 6;
 * {@code disableWorkspaceContext}/{@code disableAtPathExpansion}/{@code disableDynamicSkills}/
 * {@code disableToolsConfig} — pig owns the system prompt + toolkit; {@code disableSessionPersistence}
 * — pig's {@code AgentStateStore} stays the single persistence mechanism). Only tool-result eviction
 * is turned on. The public method surface is unchanged, so cli/web/channel/kernel are untouched.
 */
public final class PigAgent {

    private static final Logger log = LoggerFactory.getLogger(PigAgent.class);

    /** State-store partition for this single-user terminal app (the pig {@code userId}). */
    private static final String USER_ID = "pig";

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
        private LongTermMemory longTermMemory;
        private AgentStateStore stateStore;
        private PermissionContextState permissionContext;
        private int maxIters; // 0 = do not set (keep AgentScope's default)
        private int maxRetries; // <= 0 = do not set (keep AgentScope's ExecutionConfig default)
        private Model fallbackModel; // nullable
        private Path workspace; // nullable → a shared temp workspace (eviction spool root; tests)
        private ToolResultEvictionConfig toolResultEviction; // null = eviction disabled

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
         * detection + logging come in here; ephemeral long-term-memory injection/record is added
         * automatically from {@link #longTermMemory(LongTermMemory)} (appended last, so it injects
         * closest to the model). List order is onion order (first = outermost).
         */
        public Builder middlewares(List<MiddlewareBase> middlewares) {
            this.middlewares = middlewares;
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

        public PigAgent build() {
            Objects.requireNonNull(model, "model must be set before building");

            AgentStateStore effectiveStore =
                    stateStore != null ? stateStore : new InMemoryAgentStateStore();

            // Long-term memory is injected on the user side, ephemerally, via our own middleware — NOT
            // through AgentScope's long-term-memory wiring, whose injection is persisted into the
            // conversation and accumulates every turn. See EphemeralMemoryMiddleware. It is appended
            // LAST so it is the innermost reasoning middleware (memory injected closest to the model,
            // after e.g. loop-detection has counted the raw user messages).
            List<MiddlewareBase> effectiveMiddlewares = new ArrayList<>();
            if (middlewares != null) {
                effectiveMiddlewares.addAll(middlewares);
            }
            if (longTermMemory != null) {
                effectiveMiddlewares.add(new EphemeralMemoryMiddleware(longTermMemory));
            }

            // av2 Phase 5b: build a HarnessAgent VEHICLE around the same ReActAgent config. The
            // HarnessAgent.Builder setters delegate to an inner ReActAgent.Builder (toolkit/middlewares/
            // stateStore/permissionContext/maxRetries/fallbackModel/maxIters), so pig's config reaches
            // the delegate unchanged (delegate == getDelegate()). We disable every batteries-included
            // extra pig already owns and turn ON only tool-result eviction (the gap pig lacked).
            HarnessAgent.Builder hb = HarnessAgent.builder()
                    .name(name)
                    .sysPrompt(sysPrompt)
                    .model(model)
                    .stateStore(effectiveStore)
                    .toolkit(toolkit) // setter tolerates null (creates an empty Toolkit)
                    .workspace(workspace != null ? workspace : fallbackWorkspace())
                    // pig owns these — disable the native equivalents so there is no overlap:
                    .disableFilesystemTools()   // pig's guarded FileSystemTools
                    .disableShellTool()         // pig's ShellTools + command sandbox
                    .disableMemoryTools()       // pig's ephemeral memory + A4 extraction
                    .disableMemoryHooks()
                    .disableCompaction()        // pig's A5 context-engineering compaction
                    .disableSubagents()         // Phase 6 adapts multi-agent
                    .disableDynamicSubagents()
                    .disableWorkspaceContext()  // pig assembles its own byte-stable system prompt
                    .disableAtPathExpansion()
                    .disableDynamicSkills()     // pig's SkillsTool + built-in skills
                    .disableDefaultWorkspaceSkills()
                    .disableToolsConfig()       // pig manages its own Toolkit (no tools.json)
                    .disableSessionPersistence(); // pig's AgentStateStore is the single mechanism

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

            HarnessAgent harness = hb.build();
            return new PigAgent(harness, harness.getDelegate(), name, model, effectiveStore);
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
