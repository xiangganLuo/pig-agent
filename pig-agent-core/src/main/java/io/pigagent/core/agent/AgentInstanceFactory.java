package io.pigagent.core.agent;

import io.agentscope.core.hook.Hook;
import io.agentscope.core.memory.LongTermMemory;
import io.agentscope.core.model.Model;
import io.agentscope.core.tool.Toolkit;

import java.util.List;
import java.util.Objects;

/**
 * Builds an {@link AgentInstance} from an {@link AgentSpec}, one per agent — deliberately NOT
 * reusing {@code AgentFactory} (whose toolkit/hooks are fixed global singletons). The pieces
 * that differ per agent (model, tool subset, permission hook) are supplied as functions so this
 * class stays in core and unit-testable; the concrete resolvers live in the wiring layer:
 * <ul>
 *   <li>{@link ModelResolver} — maps {@code spec.modelId} to a {@link Model}, with fault-tolerant
 *       fallback to the default model (implemented over {@code ModelStore}/{@code ProtocolRegistry}).</li>
 *   <li>{@link ToolkitProvider} — builds a fresh {@link Toolkit} registering only the tools named
 *       in {@code spec.toolNames} (empty = all).</li>
 *   <li>{@link HooksProvider} — builds this agent's hooks, incl. a permission hook honoring
 *       {@code spec.permissionMode}.</li>
 * </ul>
 * Short-term memory is per-agent automatically ({@link PigAgent} builds its own); long-term
 * memory is shared and passed through.
 */
public final class AgentInstanceFactory {

    @FunctionalInterface
    public interface ModelResolver {
        Model resolve(AgentSpec spec);
    }

    @FunctionalInterface
    public interface ToolkitProvider {
        Toolkit toolkitFor(AgentSpec spec);
    }

    @FunctionalInterface
    public interface HooksProvider {
        List<Hook> hooksFor(AgentSpec spec);
    }

    private final ModelResolver models;
    private final ToolkitProvider toolkits;
    private final HooksProvider hooks;
    private final LongTermMemory longTermMemory;

    public AgentInstanceFactory(ModelResolver models, ToolkitProvider toolkits,
                                HooksProvider hooks, LongTermMemory longTermMemory) {
        this.models = Objects.requireNonNull(models, "models");
        this.toolkits = Objects.requireNonNull(toolkits, "toolkits");
        this.hooks = Objects.requireNonNull(hooks, "hooks");
        this.longTermMemory = longTermMemory; // may be null (no long-term memory)
    }

    public AgentInstance create(AgentSpec spec) {
        Objects.requireNonNull(spec, "spec");
        PigAgent agent = PigAgent.builder()
                .name(spec.name())
                .sysPrompt(spec.sysPrompt())
                .model(models.resolve(spec))
                .toolkit(toolkits.toolkitFor(spec))
                .hooks(hooks.hooksFor(spec))
                .longTermMemory(longTermMemory)
                .build();
        return new AgentInstance(spec.id(), spec, agent);
    }
}
