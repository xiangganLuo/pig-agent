package io.pigagent.core.agent;

import io.agentscope.core.hook.Hook;
import io.agentscope.core.memory.LongTermMemory;
import io.agentscope.core.model.Model;
import io.agentscope.core.tool.Toolkit;

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

    public AgentFactory(String name, String sysPrompt, Toolkit toolkit,
                        List<Hook> hooks, LongTermMemory longTermMemory) {
        this.name = name;
        this.sysPrompt = sysPrompt;
        this.toolkit = toolkit;
        this.hooks = hooks;
        this.longTermMemory = longTermMemory;
    }

    /** Build a fresh agent using the given model and the shared configuration. */
    public PigAgent create(Model model) {
        return PigAgent.builder()
                .name(name)
                .sysPrompt(sysPrompt)
                .model(model)
                .toolkit(toolkit)
                .hooks(hooks)
                .longTermMemory(longTermMemory)
                .build();
    }
}
