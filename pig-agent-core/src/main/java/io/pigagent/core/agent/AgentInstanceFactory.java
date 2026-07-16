package io.pigagent.core.agent;

import io.agentscope.core.memory.LongTermMemory;
import io.agentscope.core.middleware.MiddlewareBase;
import io.agentscope.core.model.Model;
import io.agentscope.core.permission.PermissionContextState;
import io.agentscope.core.state.AgentStateStore;
import io.agentscope.core.tool.Toolkit;
import io.agentscope.harness.agent.memory.compaction.ToolResultEvictionConfig;

import java.nio.file.Path;
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
 *   <li>{@link MiddlewareProvider} — builds this agent's middlewares (loop detection, logging, …);
 *       permission is native (the {@link PermissionContextProvider}), not a middleware.</li>
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
    public interface MiddlewareProvider {
        List<MiddlewareBase> middlewaresFor(AgentSpec spec);
    }

    /**
     * Builds this agent's native permission context (av2 Phase 4) — the 2.0 replacement for the
     * per-agent {@code ToolPermissionHook}. It receives the spec (for its {@code permissionMode}
     * override) and the already-built {@link Toolkit} (for the tool names to write rules for). The
     * concrete impl lives in the wiring layer (over pig's {@code PermissionContextFactory}).
     */
    @FunctionalInterface
    public interface PermissionContextProvider {
        PermissionContextState contextFor(AgentSpec spec, Toolkit toolkit);
    }

    private final ModelResolver models;
    private final ToolkitProvider toolkits;
    private final MiddlewareProvider middlewares;
    private final LongTermMemory longTermMemory;
    private final AgentStateStore stateStore; // nullable → per-agent in-memory default
    private final PermissionContextProvider permissionContexts; // nullable → no native context
    private final int maxRetries; // <= 0 = keep AgentScope's default (av2 Phase 5a native retry)
    private final Path workspace; // nullable → PigAgent uses a shared temp workspace (av2 5b)
    private final ToolResultEvictionConfig toolResultEviction; // null → eviction disabled (av2 5b)

    public AgentInstanceFactory(ModelResolver models, ToolkitProvider toolkits,
                                MiddlewareProvider middlewares, LongTermMemory longTermMemory) {
        this(models, toolkits, middlewares, longTermMemory, null, null, 0, null, null);
    }

    public AgentInstanceFactory(ModelResolver models, ToolkitProvider toolkits,
                                MiddlewareProvider middlewares, LongTermMemory longTermMemory,
                                AgentStateStore stateStore) {
        this(models, toolkits, middlewares, longTermMemory, stateStore, null, 0, null, null);
    }

    public AgentInstanceFactory(ModelResolver models, ToolkitProvider toolkits,
                                MiddlewareProvider middlewares, LongTermMemory longTermMemory,
                                AgentStateStore stateStore,
                                PermissionContextProvider permissionContexts) {
        this(models, toolkits, middlewares, longTermMemory, stateStore, permissionContexts, 0, null, null);
    }

    public AgentInstanceFactory(ModelResolver models, ToolkitProvider toolkits,
                                MiddlewareProvider middlewares, LongTermMemory longTermMemory,
                                AgentStateStore stateStore,
                                PermissionContextProvider permissionContexts,
                                int maxRetries) {
        this(models, toolkits, middlewares, longTermMemory, stateStore, permissionContexts, maxRetries,
                null, null);
    }

    /**
     * @param stateStore shared conversation state store (av2 Phase 3). Passing the same instance to
     *        every agent lets per-{@code (userId,sessionId)} conversation state persist across model
     *        switches and (with a {@code JsonFileAgentStateStore}) restarts. {@code null} = each agent
     *        gets its own in-memory store.
     * @param permissionContexts per-agent native permission context provider (av2 Phase 4); {@code null}
     *        = no native permission context (AgentScope default).
     * @param maxRetries native model-call retry count applied to each agent (av2 Phase 5a); {@code <= 0}
     *        keeps AgentScope's default, {@code 1} effectively disables retry.
     * @param workspace HarnessAgent workspace root (av2 Phase 5b) — the tool-result-eviction spool root;
     *        {@code null} → PigAgent uses a shared temp workspace.
     * @param toolResultEviction native tool-result-eviction config (av2 Phase 5b); {@code null} disables it.
     */
    public AgentInstanceFactory(ModelResolver models, ToolkitProvider toolkits,
                                MiddlewareProvider middlewares, LongTermMemory longTermMemory,
                                AgentStateStore stateStore,
                                PermissionContextProvider permissionContexts,
                                int maxRetries,
                                Path workspace, ToolResultEvictionConfig toolResultEviction) {
        this.models = Objects.requireNonNull(models, "models");
        this.toolkits = Objects.requireNonNull(toolkits, "toolkits");
        this.middlewares = Objects.requireNonNull(middlewares, "middlewares");
        this.longTermMemory = longTermMemory; // may be null (no long-term memory)
        this.stateStore = stateStore;
        this.permissionContexts = permissionContexts;
        this.maxRetries = maxRetries;
        this.workspace = workspace;
        this.toolResultEviction = toolResultEviction;
    }

    public AgentInstance create(AgentSpec spec) {
        Objects.requireNonNull(spec, "spec");
        Toolkit toolkit = toolkits.toolkitFor(spec);
        PigAgent agent = PigAgent.builder()
                .name(spec.name())
                .sysPrompt(spec.sysPrompt())
                .model(models.resolve(spec))
                .toolkit(toolkit)
                .middlewares(middlewares.middlewaresFor(spec))
                .longTermMemory(longTermMemory)
                .maxIters(spec.maxIters())
                .maxRetries(maxRetries)
                .stateStore(stateStore)
                .permissionContext(permissionContexts == null ? null : permissionContexts.contextFor(spec, toolkit))
                .workspace(workspace)
                .toolResultEviction(toolResultEviction)
                .build();
        return new AgentInstance(spec.id(), spec, agent);
    }
}
