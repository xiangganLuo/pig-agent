package io.pigagent.core.interrupt;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;

/**
 * A cancellation handle for a single agent turn. Created by {@link InterruptController#begin} when a
 * turn starts and cleared when it ends.
 *
 * <p><b>av2 Phase 5a.</b> The self-built {@code InterruptibleModel} decorator is gone; interruption
 * is now native. A handle carries an optional {@code interruptAction} — the native interrupt call
 * ({@code ReActAgent.interrupt(RuntimeContext)} via {@code PigAgent.interrupt}) — so
 * {@link InterruptController#interruptCurrent()} aborts the in-flight ReAct loop cleanly (no
 * half-finished result persisted). It <em>also</em> exposes {@link #onInterrupt()}, a signal the
 * kernel uses to terminate the frontend-facing event stream immediately (via {@code takeUntilOther}),
 * so a stuck/never-completing model stream still returns control to the prompt at once. Both are
 * fired together by {@link #fire()}.
 */
public final class TurnHandle {

    private static final Logger log = LoggerFactory.getLogger(TurnHandle.class);

    private final long turnId;
    private final Runnable interruptAction; // nullable → signal-only (e.g. autonomous runs)
    private final Sinks.Empty<Void> interrupt = Sinks.empty();

    TurnHandle(long turnId, Runnable interruptAction) {
        this.turnId = turnId;
        this.interruptAction = interruptAction;
    }

    public long turnId() {
        return turnId;
    }

    /** Completes (empty) when this turn is interrupted; never completes otherwise. */
    public Mono<Void> onInterrupt() {
        return interrupt.asMono();
    }

    /**
     * Fire the interrupt: run the native interrupt action (if any) then emit the stream-termination
     * signal. Idempotent — a second call after termination is a no-op. A throwing action is logged
     * and swallowed so it never blocks the stream-termination signal.
     */
    void fire() {
        if (interruptAction != null) {
            try {
                interruptAction.run();
            } catch (Exception e) {
                log.warn("Native interrupt action failed for turn {}: {}", turnId, e.getMessage());
            }
        }
        interrupt.tryEmitEmpty();
    }
}
