package io.pigagent.core.agent;

import io.agentscope.core.memory.LongTermMemory;
import io.agentscope.core.middleware.MiddlewareBase;
import io.agentscope.core.model.Model;
import io.agentscope.core.permission.PermissionContextState;
import io.agentscope.core.state.AgentStateStore;
import io.agentscope.core.tool.Toolkit;
import io.agentscope.harness.agent.memory.compaction.ToolResultEvictionConfig;
import io.agentscope.harness.agent.subagent.SubagentDeclaration;

import java.nio.file.Path;
import java.util.List;
import java.util.function.Supplier;

/**
 * Builds {@link PigAgent} instances that all share the same configuration (name, system
 * prompt, toolkit, middlewares, long-term memory) but a swappable {@code Model}.
 *
 * <p>Used to rebuild the agent when the user switches models at runtime: the toolkit, hooks
 * and {@code CompositeLongTermMemory} are reused unchanged, only the model differs. The
 * conversation is restored separately via the session layer.
 *
 * <p><b>Shared state store (av2 Phase 3).</b> An optional {@link AgentStateStore} is threaded into
 * every rebuilt {@link PigAgent}. Passing the same store instance across rebuilds is what lets
 * per-session conversation state survive a model switch (and, with a {@code JsonFileAgentStateStore},
 * a process restart). When {@code null}, each agent gets its own in-memory store (Phase-0 behavior).
 *
 * <p><b>Native retry (av2 Phase 5a).</b> The self-built {@code RetryingModel}/{@code InterruptibleModel}
 * {@code Model} decorators are gone. Retry is native ({@code maxRetries}/{@code fallbackModel} on the
 * builder → the underlying {@code ExecutionConfig} retry, which already filters transient
 * 429/5xx/timeout/IO from permanent 4xx/auth). Interruption is native too and is driven by the
 * kernel's {@code InterruptController} (the factory no longer decorates the model for it).
 */
public final class AgentFactory {

    private final String name;
    private final String sysPrompt;
    private final Toolkit toolkit;
    private final List<MiddlewareBase> middlewares;
    private final LongTermMemory longTermMemory;
    private final int maxRetries; // <= 0 = keep AgentScope's ExecutionConfig default retry
    private final Model fallbackModel; // nullable → no fallback model
    private final int maxIters; // 0 = keep AgentScope's default (see PigAgent.Builder.maxIters)
    private final AgentStateStore stateStore; // nullable → per-agent in-memory default
    // Re-evaluated on every create() (incl. model-switch rebuilds) so the rebuilt agent picks up the
    // current permission mode; nullable → no native permission context (AgentScope default). av2 P4.
    private final Supplier<PermissionContextState> permissionContextSupplier;
    private final Path workspace; // nullable → PigAgent uses a shared temp workspace (av2 5b)
    private final ToolResultEvictionConfig toolResultEviction; // null → eviction disabled (av2 5b)
    private final boolean subagentsEnabled; // false → native subagents disabled (av2 6a)
    private final List<SubagentDeclaration> subagentDeclarations; // null/empty → built-in + workspace only

    public AgentFactory(String name, String sysPrompt, Toolkit toolkit,
                        List<MiddlewareBase> middlewares, LongTermMemory longTermMemory) {
        this(name, sysPrompt, toolkit, middlewares, longTermMemory, 0, null, 0, null, null, null, null);
    }

    public AgentFactory(String name, String sysPrompt, Toolkit toolkit,
                        List<MiddlewareBase> middlewares, LongTermMemory longTermMemory, int maxRetries) {
        this(name, sysPrompt, toolkit, middlewares, longTermMemory, maxRetries, null, 0, null, null, null, null);
    }

    public AgentFactory(String name, String sysPrompt, Toolkit toolkit,
                        List<MiddlewareBase> middlewares, LongTermMemory longTermMemory, int maxRetries,
                        Model fallbackModel, int maxIters, AgentStateStore stateStore,
                        Supplier<PermissionContextState> permissionContextSupplier) {
        this(name, sysPrompt, toolkit, middlewares, longTermMemory, maxRetries, fallbackModel, maxIters,
                stateStore, permissionContextSupplier, null, null);
    }

    /**
     * @param maxRetries native model-call retry count (av2 Phase 5a); {@code <= 0} keeps AgentScope's
     *        default, {@code 1} effectively disables retry.
     * @param fallbackModel native fallback model tried after retries are exhausted; {@code null} = none.
     * @param stateStore shared conversation state store (av2 Phase 3); {@code null} = per-agent in-memory.
     * @param permissionContextSupplier supplies a freshly-built native permission context on each
     *        {@link #create(Model)} (including model-switch rebuilds), so a rebuilt agent reflects the
     *        current permission mode. {@code null} = no native permission context.
     * @param workspace HarnessAgent workspace root (av2 Phase 5b) — the tool-result-eviction spool root;
     *        {@code null} → PigAgent uses a shared temp workspace.
     * @param toolResultEviction native tool-result-eviction config (av2 Phase 5b); {@code null} disables it.
     */
    public AgentFactory(String name, String sysPrompt, Toolkit toolkit,
                        List<MiddlewareBase> middlewares, LongTermMemory longTermMemory, int maxRetries,
                        Model fallbackModel, int maxIters, AgentStateStore stateStore,
                        Supplier<PermissionContextState> permissionContextSupplier,
                        Path workspace, ToolResultEvictionConfig toolResultEviction) {
        this(name, sysPrompt, toolkit, middlewares, longTermMemory, maxRetries, fallbackModel, maxIters,
                stateStore, permissionContextSupplier, workspace, toolResultEviction, false, null);
    }

    /**
     * @param subagentsEnabled enable native subagent delegation on every built agent (av2 Phase 6a) —
     *        the built-in {@code general-purpose} + {@code agent_spawn}/… tools + {@code
     *        <workspace>/subagents/*.md} discovery. {@code false} keeps the pre-6a behavior.
     * @param subagentDeclarations code-declared subagents (pig peer specs mapped via
     *        {@code AgentSpecSubagentMapper}) surfaced in addition to built-in + workspace; only applied
     *        when {@code subagentsEnabled}. {@code null}/empty = built-in + workspace only.
     */
    public AgentFactory(String name, String sysPrompt, Toolkit toolkit,
                        List<MiddlewareBase> middlewares, LongTermMemory longTermMemory, int maxRetries,
                        Model fallbackModel, int maxIters, AgentStateStore stateStore,
                        Supplier<PermissionContextState> permissionContextSupplier,
                        Path workspace, ToolResultEvictionConfig toolResultEviction,
                        boolean subagentsEnabled, List<SubagentDeclaration> subagentDeclarations) {
        this.name = name;
        this.sysPrompt = sysPrompt;
        this.toolkit = toolkit;
        this.middlewares = middlewares;
        this.longTermMemory = longTermMemory;
        this.maxRetries = maxRetries;
        this.fallbackModel = fallbackModel;
        this.maxIters = maxIters;
        this.stateStore = stateStore;
        this.permissionContextSupplier = permissionContextSupplier;
        this.workspace = workspace;
        this.toolResultEviction = toolResultEviction;
        this.subagentsEnabled = subagentsEnabled;
        this.subagentDeclarations = subagentDeclarations;
    }

    /**
     * Build a fresh agent using the given model and the shared configuration. The model is passed
     * through untouched — retry and interruption are native to the underlying {@code ReActAgent}
     * (no {@code Model} decorators). Native retry runs <em>inside</em> a single agent invocation
     * (fresh model HTTP call), never by re-subscribing the single-flight agent.
     */
    public PigAgent create(Model model) {
        return PigAgent.builder()
                .name(name)
                .sysPrompt(sysPrompt)
                .model(model)
                .toolkit(toolkit)
                .middlewares(middlewares)
                .longTermMemory(longTermMemory)
                .maxIters(maxIters)
                .maxRetries(maxRetries)
                .fallbackModel(fallbackModel)
                .stateStore(stateStore)
                .permissionContext(permissionContextSupplier == null ? null : permissionContextSupplier.get())
                .workspace(workspace)
                .toolResultEviction(toolResultEviction)
                .subagents(subagentsEnabled)
                .subagentDeclarations(subagentDeclarations)
                .build();
    }
}
