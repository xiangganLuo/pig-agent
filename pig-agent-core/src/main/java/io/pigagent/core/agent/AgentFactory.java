package io.pigagent.core.agent;

import io.agentscope.core.hook.Hook;
import io.agentscope.core.memory.LongTermMemory;
import io.agentscope.core.model.Model;
import io.agentscope.core.tool.Toolkit;
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

    public AgentFactory(String name, String sysPrompt, Toolkit toolkit,
                        List<Hook> hooks, LongTermMemory longTermMemory) {
        this(name, sysPrompt, toolkit, hooks, longTermMemory, null);
    }

    public AgentFactory(String name, String sysPrompt, Toolkit toolkit,
                        List<Hook> hooks, LongTermMemory longTermMemory, RetryPolicy retryPolicy) {
        this.name = name;
        this.sysPrompt = sysPrompt;
        this.toolkit = toolkit;
        this.hooks = hooks;
        this.longTermMemory = longTermMemory;
        this.retryPolicy = retryPolicy;
    }

    /**
     * Build a fresh agent using the given model and the shared configuration. When a retry policy
     * is set, the model is wrapped in a {@link RetryingModel} so transient failures are retried
     * <em>inside</em> a single agent invocation (never by re-subscribing the single-flight agent).
     */
    public PigAgent create(Model model) {
        Model effectiveModel = retryPolicy == null ? model : new RetryingModel(model, retryPolicy);
        return PigAgent.builder()
                .name(name)
                .sysPrompt(sysPrompt)
                .model(effectiveModel)
                .toolkit(toolkit)
                .hooks(hooks)
                .longTermMemory(longTermMemory)
                .build();
    }
}
