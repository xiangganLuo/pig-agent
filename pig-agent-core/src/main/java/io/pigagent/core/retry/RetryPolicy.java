package io.pigagent.core.retry;

import reactor.core.publisher.Flux;
import reactor.util.retry.Retry;

import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Retries a model call on <em>transient</em> failures (see {@link TransientErrorClassifier}):
 * per-attempt timeout, bounded retries, exponential backoff with a cap.
 *
 * <p>Correctness guards:
 * <ul>
 *   <li><b>Permanent errors</b> (4xx/auth) are not retried — they propagate immediately.</li>
 *   <li><b>Pre-emission guard</b>: once the stream has emitted any element, a later failure is
 *       NOT retried (re-subscribing would make the model regenerate from scratch and duplicate
 *       already-shown output). Only failures before the first element are retried.</li>
 *   <li><b>Disabled</b> policy returns the source unchanged (no timeout, no retry) — legacy
 *       behavior.</li>
 * </ul>
 */
public final class RetryPolicy {

    /** Notified before each retry so a frontend can show progress. */
    @FunctionalInterface
    public interface RetryListener {
        void onRetry(long attempt, long maxRetries, Throwable cause, Duration backoff);
    }

    private final boolean enabled;
    private final int maxRetries;
    private final Duration perAttemptTimeout;
    private final Duration firstBackoff;
    private final Duration maxBackoff;
    private final TransientErrorClassifier classifier;
    private final RetryListener listener;

    public RetryPolicy(boolean enabled, int maxRetries, Duration perAttemptTimeout,
                       Duration firstBackoff, Duration maxBackoff,
                       TransientErrorClassifier classifier, RetryListener listener) {
        this.enabled = enabled;
        this.maxRetries = maxRetries;
        this.perAttemptTimeout = perAttemptTimeout;
        this.firstBackoff = firstBackoff == null ? Duration.ofMillis(500) : firstBackoff;
        this.maxBackoff = maxBackoff == null ? Duration.ofSeconds(8) : maxBackoff;
        this.classifier = Objects.requireNonNull(classifier, "classifier");
        this.listener = listener;
    }

    /** Wrap a source stream with per-attempt timeout + transient-error retry. */
    public <T> Flux<T> apply(Flux<T> source) {
        if (!enabled) {
            return source; // legacy behavior: no timeout, no retry
        }
        AtomicBoolean emitted = new AtomicBoolean(false);
        Flux<T> attempt = source.doOnNext(x -> emitted.set(true));
        // Per-attempt timeout is opt-in (> 0). It is DISABLED by default because a client-side
        // timeout here is unsafe with the current non-interruptible ReActAgent: Flux.timeout only
        // cancels the downstream subscription while the underlying agent keeps running, so a retry
        // re-subscribes into a still-running agent ("Agent is still running"), and a slow-but-
        // healthy model (large context, tools) legitimately exceeds short timeouts. A true
        // per-attempt hard timeout waits on the interruptible-run spike. Retry is driven by real
        // transient error signals (5xx/network), which arrive only after the agent has terminated.
        if (perAttemptTimeout != null && !perAttemptTimeout.isZero() && !perAttemptTimeout.isNegative()) {
            attempt = attempt.timeout(perAttemptTimeout);
        }
        if (maxRetries <= 0) {
            return attempt; // timeout only, no retries configured
        }
        Retry retry = Retry.backoff(maxRetries, firstBackoff)
                .maxBackoff(maxBackoff)
                // Retry only transient failures that occurred before any content was emitted.
                .filter(err -> classifier.isTransient(err) && !emitted.get())
                .doBeforeRetry(sig -> {
                    if (listener != null) {
                        long attemptNo = sig.totalRetries() + 1;
                        listener.onRetry(attemptNo, maxRetries, sig.failure(),
                                approxBackoff(attemptNo));
                    }
                });
        return attempt.retryWhen(retry);
    }

    /** Approximate backoff Reactor will wait before the given attempt (display hint; ignores jitter). */
    private Duration approxBackoff(long attemptNo) {
        long millis = firstBackoff.toMillis() * (1L << Math.min(attemptNo - 1, 30));
        return Duration.ofMillis(Math.min(millis, maxBackoff.toMillis()));
    }
}
