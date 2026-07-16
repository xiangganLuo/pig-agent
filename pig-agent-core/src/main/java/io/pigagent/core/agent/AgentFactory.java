package io.pigagent.core.agent;

import io.agentscope.core.hook.Hook;
import io.agentscope.core.memory.LongTermMemory;
import io.agentscope.core.model.Model;
import io.agentscope.core.permission.PermissionContextState;
import io.agentscope.core.state.AgentStateStore;
import io.agentscope.core.tool.Toolkit;
import io.pigagent.core.interrupt.InterruptController;
import io.pigagent.core.interrupt.InterruptibleModel;
import io.pigagent.core.retry.RetryPolicy;
import io.pigagent.core.retry.RetryingModel;

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
 */
public final class AgentFactory {

    private final String name;
    private final String sysPrompt;
    private final Toolkit toolkit;
    private final List<Hook> hooks;
    private final LongTermMemory longTermMemory;
    private final RetryPolicy retryPolicy; // nullable
    private final InterruptController interruptController; // nullable
    private final int maxIters; // 0 = keep AgentScope's default (see PigAgent.Builder.maxIters)
    private final AgentStateStore stateStore; // nullable → per-agent in-memory default
    // Re-evaluated on every create() (incl. model-switch rebuilds) so the rebuilt agent picks up the
    // current permission mode; nullable → no native permission context (AgentScope default). av2 P4.
    private final Supplier<PermissionContextState> permissionContextSupplier;

    public AgentFactory(String name, String sysPrompt, Toolkit toolkit,
                        List<Hook> hooks, LongTermMemory longTermMemory) {
        this(name, sysPrompt, toolkit, hooks, longTermMemory, null, null, 0);
    }

    public AgentFactory(String name, String sysPrompt, Toolkit toolkit,
                        List<Hook> hooks, LongTermMemory longTermMemory, RetryPolicy retryPolicy) {
        this(name, sysPrompt, toolkit, hooks, longTermMemory, retryPolicy, null, 0);
    }

    public AgentFactory(String name, String sysPrompt, Toolkit toolkit,
                        List<Hook> hooks, LongTermMemory longTermMemory, RetryPolicy retryPolicy,
                        InterruptController interruptController) {
        this(name, sysPrompt, toolkit, hooks, longTermMemory, retryPolicy, interruptController, 0);
    }

    public AgentFactory(String name, String sysPrompt, Toolkit toolkit,
                        List<Hook> hooks, LongTermMemory longTermMemory, RetryPolicy retryPolicy,
                        InterruptController interruptController, int maxIters) {
        this(name, sysPrompt, toolkit, hooks, longTermMemory, retryPolicy, interruptController,
                maxIters, null);
    }

    public AgentFactory(String name, String sysPrompt, Toolkit toolkit,
                        List<Hook> hooks, LongTermMemory longTermMemory, RetryPolicy retryPolicy,
                        InterruptController interruptController, int maxIters,
                        AgentStateStore stateStore) {
        this(name, sysPrompt, toolkit, hooks, longTermMemory, retryPolicy, interruptController,
                maxIters, stateStore, null);
    }

    /**
     * @param permissionContextSupplier supplies a freshly-built native permission context on each
     *        {@link #create(Model)} (including model-switch rebuilds), so a rebuilt agent reflects the
     *        current permission mode. {@code null} = no native permission context.
     */
    public AgentFactory(String name, String sysPrompt, Toolkit toolkit,
                        List<Hook> hooks, LongTermMemory longTermMemory, RetryPolicy retryPolicy,
                        InterruptController interruptController, int maxIters,
                        AgentStateStore stateStore,
                        Supplier<PermissionContextState> permissionContextSupplier) {
        this.name = name;
        this.sysPrompt = sysPrompt;
        this.toolkit = toolkit;
        this.hooks = hooks;
        this.longTermMemory = longTermMemory;
        this.retryPolicy = retryPolicy;
        this.interruptController = interruptController;
        this.maxIters = maxIters;
        this.stateStore = stateStore;
        this.permissionContextSupplier = permissionContextSupplier;
    }

    /**
     * Build a fresh agent using the given model and the shared configuration. The model is wrapped
     * in the decoration chain {@code RetryingModel(InterruptibleModel(model))} when the respective
     * collaborators are set: interrupt/cancel is per single attempt (inner), retry is the outer
     * layer. Retries happen <em>inside</em> a single agent invocation (never by re-subscribing the
     * single-flight agent).
     */
    public PigAgent create(Model model) {
        Model effectiveModel = decorate(model);
        return PigAgent.builder()
                .name(name)
                .sysPrompt(sysPrompt)
                .model(effectiveModel)
                .toolkit(toolkit)
                .hooks(hooks)
                .longTermMemory(longTermMemory)
                .maxIters(maxIters)
                .stateStore(stateStore)
                .permissionContext(permissionContextSupplier == null ? null : permissionContextSupplier.get())
                .build();
    }

    /** Wrap the raw model with the interrupt (inner) and retry (outer) decorators, if configured. */
    private Model decorate(Model model) {
        Model effective = model;
        if (interruptController != null) {
            effective = new InterruptibleModel(effective, interruptController);
        }
        if (retryPolicy != null) {
            effective = new RetryingModel(effective, retryPolicy);
        }
        return effective;
    }
}
