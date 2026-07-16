package io.pigagent.core.interrupt;

/**
 * Signals that a turn's event stream was terminated because the turn was interrupted (via
 * {@code AgentKernel.interruptCurrent()}).
 *
 * <p><b>av2 Phase 5a.</b> The kernel uses this to end the frontend-facing event stream immediately
 * ({@code takeUntilOther}) when a turn is interrupted, in addition to driving native
 * {@code ReActAgent.interrupt(...)} for a clean cooperative abort. Because the stream errors (rather
 * than completing) the frontend distinguishes an interrupt from normal completion and no
 * half-finished output is treated as a result.
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
