package io.pigagent.core.interrupt;

import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Tracks the currently active turn's {@link TurnHandle} so a frontend stop key (via
 * {@code AgentKernel.interruptCurrent()}) can cancel the in-flight model call, and so the
 * interruptible {@code Model} decorator can discover which turn it belongs to.
 *
 * <p>Concurrency (design R3): a single {@link AtomicReference} holds the current handle. {@link
 * #begin()} registers a fresh handle; {@link #end(TurnHandle)} clears it only if it is still the
 * current one (compare-and-set), so a stale end from a finished turn never clobbers a newer turn.
 * {@link #interruptCurrent()} acts on whatever is current at call time, or is a no-op when idle.
 */
public final class InterruptController {

    private final AtomicReference<TurnHandle> current = new AtomicReference<>();
    private final AtomicLong sequence = new AtomicLong();

    /** Register a fresh turn as the current one and return its handle. */
    public TurnHandle begin() {
        TurnHandle handle = new TurnHandle(sequence.incrementAndGet());
        current.set(handle);
        return handle;
    }

    /** Clear the given handle if it is still current (no-op if a newer turn already replaced it). */
    public void end(TurnHandle handle) {
        if (handle != null) {
            current.compareAndSet(handle, null);
        }
    }

    /** The handle for the in-flight turn, if any (read by the interruptible model decorator). */
    public Optional<TurnHandle> currentTurn() {
        return Optional.ofNullable(current.get());
    }

    /**
     * Request interruption of the current turn.
     *
     * @return true if a turn was in flight and its interrupt was fired; false when idle (no-op).
     */
    public boolean interruptCurrent() {
        TurnHandle handle = current.get();
        if (handle == null) {
            return false;
        }
        handle.fire();
        return true;
    }
}
