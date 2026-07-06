package io.pigagent.core.agent;

import java.util.List;
import java.util.Objects;

/**
 * Declarative, immutable definition of an agent: identity, persona, the tool subset it may
 * use, its permission mode, which model it runs on, and — for autonomous "digital employee"
 * agents — a schedule/mandate and unattended-run settings. Persisted as {@code
 * workspace/agents/{id}.md} and managed at runtime by {@code AgentRegistry}.
 *
 * <p>Conventions:
 * <ul>
 *   <li>empty {@link #toolNames()} means "use all available tools" ({@link #usesAllTools()});</li>
 *   <li>null {@link #permissionMode()} means "use the global default mode";</li>
 *   <li>null/blank {@link #modelId()} (or one pointing at a deleted model) means "use the default model";</li>
 *   <li><b>Autonomous (phase 2):</b> null/blank {@link #schedule()} means interactive; a non-blank
 *       schedule (a cron expression) makes it an autonomous agent ({@link #isAutonomous()}) that
 *       runs its {@link #mandate()} on schedule. {@code schedule} is a String here (not
 *       {@code TaskSchedule}) to keep core free of the task module; the runner parses it.</li>
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
        int maxIters,
        String mandate,
        String schedule,
        List<String> commandAllowlist,
        int timeoutSeconds,
        long lastRunAtEpochMs
) {

    public static final int DEFAULT_MAX_ITERS = 10;

    public AgentSpec {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(name, "name");
        sysPrompt = sysPrompt == null ? "" : sysPrompt;
        toolNames = toolNames == null ? List.of() : List.copyOf(toolNames);
        maxIters = maxIters <= 0 ? DEFAULT_MAX_ITERS : maxIters;
        commandAllowlist = commandAllowlist == null ? List.of() : List.copyOf(commandAllowlist);
        timeoutSeconds = Math.max(0, timeoutSeconds);
        lastRunAtEpochMs = Math.max(0, lastRunAtEpochMs);
    }

    /** Backward-compatible constructor for interactive agents (no autonomous fields). */
    public AgentSpec(String id, String name, String sysPrompt, List<String> toolNames,
                     String permissionMode, String modelId, int maxIters) {
        this(id, name, sysPrompt, toolNames, permissionMode, modelId, maxIters,
                null, null, List.of(), 0, 0L);
    }

    /** A fresh interactive spec with sensible defaults. */
    public static AgentSpec create(String id, String name) {
        return new AgentSpec(id, name, "", List.of(), null, null, DEFAULT_MAX_ITERS);
    }

    /** Empty tool whitelist means the agent may use every registered tool. */
    public boolean usesAllTools() {
        return toolNames.isEmpty();
    }

    /** A non-blank schedule makes this an autonomous (scheduled) agent. */
    public boolean isAutonomous() {
        return schedule != null && !schedule.isBlank();
    }

    public AgentSpec withName(String value) {
        return new AgentSpec(id, value, sysPrompt, toolNames, permissionMode, modelId, maxIters,
                mandate, schedule, commandAllowlist, timeoutSeconds, lastRunAtEpochMs);
    }

    public AgentSpec withSysPrompt(String value) {
        return new AgentSpec(id, name, value, toolNames, permissionMode, modelId, maxIters,
                mandate, schedule, commandAllowlist, timeoutSeconds, lastRunAtEpochMs);
    }

    public AgentSpec withToolNames(List<String> value) {
        return new AgentSpec(id, name, sysPrompt, value, permissionMode, modelId, maxIters,
                mandate, schedule, commandAllowlist, timeoutSeconds, lastRunAtEpochMs);
    }

    public AgentSpec withPermissionMode(String value) {
        return new AgentSpec(id, name, sysPrompt, toolNames, value, modelId, maxIters,
                mandate, schedule, commandAllowlist, timeoutSeconds, lastRunAtEpochMs);
    }

    public AgentSpec withModelId(String value) {
        return new AgentSpec(id, name, sysPrompt, toolNames, permissionMode, value, maxIters,
                mandate, schedule, commandAllowlist, timeoutSeconds, lastRunAtEpochMs);
    }

    public AgentSpec withMaxIters(int value) {
        return new AgentSpec(id, name, sysPrompt, toolNames, permissionMode, modelId, value,
                mandate, schedule, commandAllowlist, timeoutSeconds, lastRunAtEpochMs);
    }

    public AgentSpec withMandate(String value) {
        return new AgentSpec(id, name, sysPrompt, toolNames, permissionMode, modelId, maxIters,
                value, schedule, commandAllowlist, timeoutSeconds, lastRunAtEpochMs);
    }

    public AgentSpec withSchedule(String value) {
        return new AgentSpec(id, name, sysPrompt, toolNames, permissionMode, modelId, maxIters,
                mandate, value, commandAllowlist, timeoutSeconds, lastRunAtEpochMs);
    }

    public AgentSpec withCommandAllowlist(List<String> value) {
        return new AgentSpec(id, name, sysPrompt, toolNames, permissionMode, modelId, maxIters,
                mandate, schedule, value, timeoutSeconds, lastRunAtEpochMs);
    }

    public AgentSpec withTimeoutSeconds(int value) {
        return new AgentSpec(id, name, sysPrompt, toolNames, permissionMode, modelId, maxIters,
                mandate, schedule, commandAllowlist, value, lastRunAtEpochMs);
    }

    public AgentSpec withLastRunAtEpochMs(long value) {
        return new AgentSpec(id, name, sysPrompt, toolNames, permissionMode, modelId, maxIters,
                mandate, schedule, commandAllowlist, timeoutSeconds, value);
    }
}
