package io.pigagent.core.agent;

import io.agentscope.core.hook.Hook;
import io.agentscope.core.memory.LongTermMemory;
import io.agentscope.core.model.Model;
import io.agentscope.core.tool.Toolkit;
import io.pigagent.core.interrupt.InterruptController;
import io.pigagent.core.interrupt.InterruptibleModel;
import io.pigagent.core.retry.RetryPolicy;
import io.pigagent.core.retry.RetryingModel;

import java.util.List;

/**
 * Builds {@link PigAgent} instances that all share the same configuration (name, system
 * prompt, toolkit, hooks, long-term memory) but a swappable {@code Model}.
 *
 * <p>Used to rebuild the agent when the user switches models at runtime: the toolkit, hooks
 * and {@code CompositeLongTermMemory} are reused unchanged, only the model differs. The
 * conversation is restored separately via the session layer.
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
        this.name = name;
        this.sysPrompt = sysPrompt;
        this.toolkit = toolkit;
        this.hooks = hooks;
        this.longTermMemory = longTermMemory;
        this.retryPolicy = retryPolicy;
        this.interruptController = interruptController;
        this.maxIters = maxIters;
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
