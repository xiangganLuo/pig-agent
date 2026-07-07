package io.pigagent.core.interrupt;

import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;

/**
 * A cancellation handle for a single agent turn. Created by {@link InterruptController#begin()} when
 * a turn starts and cleared when it ends. A model decorator subscribes to {@link #onInterrupt()};
 * {@link InterruptController#interruptCurrent()} fires it, cancelling the in-flight model call.
 *
 * <p>The underlying {@link Sinks.Empty} replays its terminal signal to late subscribers, so a retry
 * attempt that re-subscribes after an interrupt is aborted immediately too — an interrupt kills the
 * whole turn, not just the current attempt.
 */
public final class TurnHandle {

    private final long turnId;
    private final Sinks.Empty<Void> interrupt = Sinks.empty();

    TurnHandle(long turnId) {
        this.turnId = turnId;
    }

    public long turnId() {
        return turnId;
    }

    /** Completes (empty) when this turn is interrupted; never completes otherwise. */
    public Mono<Void> onInterrupt() {
        return interrupt.asMono();
    }

    /** Fire the interrupt signal. Idempotent — a second call after termination is a no-op. */
    void fire() {
        interrupt.tryEmitEmpty();
    }
}
