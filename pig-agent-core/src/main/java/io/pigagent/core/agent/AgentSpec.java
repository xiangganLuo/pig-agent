package io.pigagent.core.agent;

import java.util.List;
import java.util.Objects;

/**
 * Declarative, immutable definition of an agent: identity, persona, the tool subset it may
 * use, its permission mode, and which model it runs on. Persisted as {@code
 * workspace/agents/{id}.md} and managed at runtime by {@code AgentRegistry}.
 *
 * <p>Design conventions (see {@code docs/design/agent-management-design.md}, phase 1):
 * <ul>
 *   <li>empty {@link #toolNames()} means "use all available tools" ({@link #usesAllTools()});</li>
 *   <li>null {@link #permissionMode()} means "use the global default mode";</li>
 *   <li>null {@link #modelId()} (or one pointing at a deleted model) means "use the default
 *       model" — resolution is fault-tolerant, done by the registry, not here.</li>
 * </ul>
 * Mutate via {@code withXxx} copy methods; never in place.
 */
public record AgentSpec(
        String id,
        String name,
        String sysPrompt,
        List<String> toolNames,
        String permissionMode,
        String modelId,
        int maxIters
) {

    public static final int DEFAULT_MAX_ITERS = 10;

    public AgentSpec {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(name, "name");
        sysPrompt = sysPrompt == null ? "" : sysPrompt;
        toolNames = toolNames == null ? List.of() : List.copyOf(toolNames);
        maxIters = maxIters <= 0 ? DEFAULT_MAX_ITERS : maxIters;
    }

    /** A fresh spec with sensible defaults: all tools, global permission mode, default model. */
    public static AgentSpec create(String id, String name) {
        return new AgentSpec(id, name, "", List.of(), null, null, DEFAULT_MAX_ITERS);
    }

    /** Empty tool whitelist means the agent may use every registered tool. */
    public boolean usesAllTools() {
        return toolNames.isEmpty();
    }

    public AgentSpec withName(String value) {
        return new AgentSpec(id, value, sysPrompt, toolNames, permissionMode, modelId, maxIters);
    }

    public AgentSpec withSysPrompt(String value) {
        return new AgentSpec(id, name, value, toolNames, permissionMode, modelId, maxIters);
    }

    public AgentSpec withToolNames(List<String> value) {
        return new AgentSpec(id, name, sysPrompt, value, permissionMode, modelId, maxIters);
    }

    public AgentSpec withPermissionMode(String value) {
        return new AgentSpec(id, name, sysPrompt, toolNames, value, modelId, maxIters);
    }

    public AgentSpec withModelId(String value) {
        return new AgentSpec(id, name, sysPrompt, toolNames, permissionMode, value, maxIters);
    }

    public AgentSpec withMaxIters(int value) {
        return new AgentSpec(id, name, sysPrompt, toolNames, permissionMode, modelId, value);
    }
}
