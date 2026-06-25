package io.pigagent.session;

/**
 * Rebuilds the live agent so it uses a given model. Implemented by the model module; the
 * session manager calls it when activating a session so the session's bound model (or the
 * global default) takes effect before the conversation is reloaded.
 */
public interface AgentModelSwitcher {

    /**
     * Ensure the live agent uses the model with the given id, rebuilding it if necessary.
     * A {@code null} id means "use the global default model". Implementations must be a no-op
     * when the requested model is already active, and must keep the current agent if the
     * rebuild fails.
     */
    void ensureModel(String modelId);
}
