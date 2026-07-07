package io.pigagent.core.retry;

import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Group 4 (interruptible-run): with {@code per-attempt-timeout-seconds > 0} the per-attempt timeout
 * is safe to enable — a stalled attempt is interrupted (its subscription cancelled) and retried per
 * the backoff policy, while a slow-but-healthy attempt that produces within the window is untouched,
 * and an attempt that already emitted content is NOT retried on a later timeout (no duplicate output).
 */
class PerAttemptTimeoutTest {

    private static final Duration FAST_BACKOFF = Duration.ofMillis(1);

    private RetryPolicy withTimeout(Duration timeout, int maxRetries) {
        return new RetryPolicy(true, maxRetries, timeout, FAST_BACKOFF, FAST_BACKOFF,
                new TransientErrorClassifier(), null);
    }

    @Test
    void enabledTimeout_stalledAttempt_isInterruptedAndRetried() {
        // attempt 1 stalls past the timeout (no signal) → interrupted + retried;
        // attempt 2 produces immediately → success.
        AtomicInteger subs = new AtomicInteger();
        Flux<String> source = Flux.defer(() -> {
            int n = subs.incrementAndGet();
            if (n == 1) {
                return Flux.just("late").delayElements(Duration.ofMillis(500));
            }
            return Flux.just("ok");
        });

        List<String> out = withTimeout(Duration.ofMillis(60), 5).apply(source).collectList().block();

        assertThat(subs.get()).isEqualTo(2); // first attempt timed out, second succeeded
        assertThat(out).containsExactly("ok");
    }

    @Test
    void enabledTimeout_slowButHealthy_withinWindow_isNotRetried() {
        // The single attempt produces within the timeout window → no interruption, no retry.
        AtomicInteger subs = new AtomicInteger();
        Flux<String> source = Flux.defer(() -> {
            subs.incrementAndGet();
            return Flux.just("ok").delayElements(Duration.ofMillis(30));
        });

        List<String> out = withTimeout(Duration.ofMillis(500), 5).apply(source).collectList().block();

        assertThat(out).containsExactly("ok");
        assertThat(subs.get()).isEqualTo(1); // healthy slow response not falsely timed out
    }

    @Test
    void enabledTimeout_afterContentEmitted_isNotRetried() {
        // attempt emits "partial" then stalls; the later timeout MUST NOT retry (would duplicate).
        AtomicInteger subs = new AtomicInteger();
        Flux<String> source = Flux.defer(() -> {
            subs.incrementAndGet();
            return Flux.concat(Flux.just("partial"), Flux.never());
        });

        List<String> collected = new ArrayList<>();
        assertThatThrownBy(() ->
                withTimeout(Duration.ofMillis(60), 5).apply(source).doOnNext(collected::add).blockLast())
                .isInstanceOf(Exception.class);

        assertThat(subs.get()).isEqualTo(1);              // no retry after content emitted
        assertThat(collected).containsExactly("partial"); // no duplicate re-emission
    }

    @Test
    void disabledTimeout_default_behaviorUnchanged() {
        // per-attempt-timeout 0 (default): a slow success passes through with a single subscription.
        AtomicInteger subs = new AtomicInteger();
        Flux<String> slow = Flux.defer(() -> {
            subs.incrementAndGet();
            return Flux.just("ok").delayElements(Duration.ofMillis(80));
        });

        List<String> out = withTimeout(Duration.ZERO, 5).apply(slow).collectList().block();

        assertThat(out).containsExactly("ok");
        assertThat(subs.get()).isEqualTo(1);
    }
}
