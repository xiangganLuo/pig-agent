package io.pigagent.core.agent;

import io.agentscope.core.hook.Hook;
import io.agentscope.core.memory.LongTermMemory;
import io.agentscope.core.model.Model;
import io.agentscope.core.permission.PermissionContextState;
import io.agentscope.core.state.AgentStateStore;
import io.agentscope.core.tool.Toolkit;

import java.util.List;
import java.util.function.Supplier;

/**
 * Builds {@link PigAgent} instances that all share the same configuration (name, system
 * prompt, toolkit, hooks, long-term memory) but a swappable {@code Model}.
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
    private final List<Hook> hooks;
    private final LongTermMemory longTermMemory;
    private final int maxRetries; // <= 0 = keep AgentScope's ExecutionConfig default retry
    private final Model fallbackModel; // nullable → no fallback model
    private final int maxIters; // 0 = keep AgentScope's default (see PigAgent.Builder.maxIters)
    private final AgentStateStore stateStore; // nullable → per-agent in-memory default
    // Re-evaluated on every create() (incl. model-switch rebuilds) so the rebuilt agent picks up the
    // current permission mode; nullable → no native permission context (AgentScope default). av2 P4.
    private final Supplier<PermissionContextState> permissionContextSupplier;

    public AgentFactory(String name, String sysPrompt, Toolkit toolkit,
                        List<Hook> hooks, LongTermMemory longTermMemory) {
        this(name, sysPrompt, toolkit, hooks, longTermMemory, 0, null, 0, null, null);
    }

    public AgentFactory(String name, String sysPrompt, Toolkit toolkit,
                        List<Hook> hooks, LongTermMemory longTermMemory, int maxRetries) {
        this(name, sysPrompt, toolkit, hooks, longTermMemory, maxRetries, null, 0, null, null);
    }

    /**
     * @param maxRetries native model-call retry count (av2 Phase 5a); {@code <= 0} keeps AgentScope's
     *        default, {@code 1} effectively disables retry.
     * @param fallbackModel native fallback model tried after retries are exhausted; {@code null} = none.
     * @param stateStore shared conversation state store (av2 Phase 3); {@code null} = per-agent in-memory.
     * @param permissionContextSupplier supplies a freshly-built native permission context on each
     *        {@link #create(Model)} (including model-switch rebuilds), so a rebuilt agent reflects the
     *        current permission mode. {@code null} = no native permission context.
     */
    public AgentFactory(String name, String sysPrompt, Toolkit toolkit,
                        List<Hook> hooks, LongTermMemory longTermMemory, int maxRetries,
                        Model fallbackModel, int maxIters, AgentStateStore stateStore,
                        Supplier<PermissionContextState> permissionContextSupplier) {
        this.name = name;
        this.sysPrompt = sysPrompt;
        this.toolkit = toolkit;
        this.hooks = hooks;
        this.longTermMemory = longTermMemory;
        this.maxRetries = maxRetries;
        this.fallbackModel = fallbackModel;
        this.maxIters = maxIters;
        this.stateStore = stateStore;
        this.permissionContextSupplier = permissionContextSupplier;
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
                .hooks(hooks)
                .longTermMemory(longTermMemory)
                .maxIters(maxIters)
                .maxRetries(maxRetries)
                .fallbackModel(fallbackModel)
                .stateStore(stateStore)
                .permissionContext(permissionContextSupplier == null ? null : permissionContextSupplier.get())
                .build();
    }
}
