package io.pigagent.core.interrupt;

/**
 * Signals that a model call was cancelled because its turn was interrupted (via
 * {@code AgentKernel.interruptCurrent()} or a per-attempt timeout that maps to an interrupt).
 *
 * <p>Deliberately NOT a transient error: {@code TransientErrorClassifier} does not treat it as
 * retryable, so an interrupt ends the turn instead of triggering a re-attempt. Because the model
 * stream errors (rather than completing), the {@code ReActAgent} invocation errors and no
 * {@code AGENT_RESULT} is written to the conversation history — the half-finished output is dropped.
 */
public final class TurnInterruptedException extends RuntimeException {

    private final long turnId;

    public TurnInterruptedException(long turnId) {
        super("Model call interrupted (turn " + turnId + ")");
        this.turnId = turnId;
    }

    public long turnId() {
        return turnId;
    }
}
