package io.pigagent.core.memory.extraction;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * A tiny debouncing scheduler that keeps memory extraction <b>off the turn's critical path</b>.
 *
 * <p>One daemon thread + a <b>single pending slot</b>: {@link #submit(Runnable)} replaces the
 * pending job and (re)schedules it {@code debounceMs} in the future, cancelling the prior timer — so
 * a rapid burst of records <b>coalesces</b> to a single execution after things go quiet. Because the
 * decorator's job closes over the latest full-conversation snapshot, the coalesced run still sees
 * the burst's recent turns (via the pipeline's recent window).
 *
 * <p>Lifecycle safety: {@link #flush()} runs the pending job immediately (used on session-tier
 * changes/shutdown), and {@link #close()} flushes then shuts the thread down and awaits termination
 * — so no work is silently dropped and no thread leaks. All state transitions are guarded by an
 * intrinsic lock; a job that throws is caught and logged (never kills the worker thread).
 */
public final class AsyncMemoryExtractionScheduler implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(AsyncMemoryExtractionScheduler.class);
    private static final long AWAIT_SECONDS = 5;

    private final ScheduledExecutorService executor;
    private final long debounceMs;
    private final Object lock = new Object();

    private Runnable pending;            // guarded by lock
    private ScheduledFuture<?> future;   // guarded by lock
    private boolean closed;              // guarded by lock

    public AsyncMemoryExtractionScheduler(long debounceMs) {
        this.debounceMs = Math.max(0, debounceMs);
        this.executor = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "memory-extraction");
            t.setDaemon(true);
            return t;
        });
    }

    /** Queue a job, coalescing with any still-pending one and (re)starting the debounce timer. */
    public void submit(Runnable job) {
        if (job == null) {
            return;
        }
        synchronized (lock) {
            if (closed) {
                return;
            }
            pending = job;
            if (future != null) {
                future.cancel(false);
            }
            future = executor.schedule(this::fire, debounceMs, TimeUnit.MILLISECONDS);
        }
    }

    private void fire() {
        Runnable job;
        synchronized (lock) {
            job = pending;
            pending = null;
            future = null;
        }
        runSafely(job);
    }

    /** Run the pending job now (if any), on the calling thread — used before switch/shutdown. */
    public void flush() {
        Runnable job;
        synchronized (lock) {
            if (future != null) {
                future.cancel(false);
                future = null;
            }
            job = pending;
            pending = null;
        }
        runSafely(job);
    }

    private void runSafely(Runnable job) {
        if (job == null) {
            return;
        }
        try {
            job.run();
        } catch (Exception e) {
            log.warn("Async memory extraction job failed (skipped): {}", e.getMessage());
        }
    }

    @Override
    public void close() {
        synchronized (lock) {
            closed = true;
        }
        flush(); // run any last pending job so shutdown does not silently drop it
        executor.shutdown();
        try {
            if (!executor.awaitTermination(AWAIT_SECONDS, TimeUnit.SECONDS)) {
                executor.shutdownNow();
            }
        } catch (InterruptedException e) {
            executor.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }
}
