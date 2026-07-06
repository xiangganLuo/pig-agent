package io.pigagent.core.agent.kernel;

/**
 * An event emitted by {@link AgentKernel} for observers (CLI status, future Web console) —
 * session/run/report lifecycle. Frontends subscribe via {@link AgentKernel#subscribeEvents()}.
 */
public record KernelEvent(Type type, String agentId, String message) {

    public enum Type {
        AGENT_CREATED,
        AGENT_DELETED,
        AGENT_SWITCHED,
        CHAT_STARTED,
        RUN_STARTED,
        RUN_FINISHED,
        REPORT
    }

    public static KernelEvent of(Type type, String agentId, String message) {
        return new KernelEvent(type, agentId, message == null ? "" : message);
    }
}
