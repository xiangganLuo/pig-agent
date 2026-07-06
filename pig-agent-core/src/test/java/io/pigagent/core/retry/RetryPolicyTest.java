package io.pigagent.core.retry;

import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RetryPolicyTest {

    private static final Duration FAST = Duration.ofMillis(1);
    private static final Duration GENEROUS_TIMEOUT = Duration.ofSeconds(30);

    private RetryPolicy policy(boolean enabled, int maxRetries, List<Long> retryLog) {
        return new RetryPolicy(enabled, maxRetries, GENEROUS_TIMEOUT, FAST, FAST,
                new TransientErrorClassifier(),
                (attempt, max, cause, backoff) -> {
                    if (retryLog != null) {
                        retryLog.add(attempt);
                    }
                });
    }

    @Test
    void transientError_retriedUpToMax_thenThrows() {
        // Arrange — a source that always fails transiently; count subscriptions.
        AtomicInteger subs = new AtomicInteger();
        Flux<String> source = Flux.defer(() -> {
            subs.incrementAndGet();
            return Flux.error(new RuntimeException("502 upstream_error"));
        });

        // Act + Assert
        assertThatThrownBy(() -> policy(true, 3, null).apply(source).blockLast())
                .isInstanceOf(Exception.class);
        // initial attempt + 3 retries = 4 subscriptions
        assertThat(subs.get()).isEqualTo(4);
    }

    @Test
    void permanentError_notRetried() {
        AtomicInteger subs = new AtomicInteger();
        Flux<String> source = Flux.defer(() -> {
            subs.incrementAndGet();
            return Flux.error(new RuntimeException("401 Unauthorized"));
        });

        assertThatThrownBy(() -> policy(true, 5, null).apply(source).blockLast())
                .isInstanceOf(Exception.class);
        assertThat(subs.get()).isEqualTo(1); // no retry on permanent error
    }

    @Test
    void preEmissionGuard_retriesBeforeContent_notAfter() {
        // attempt 1: error before emitting → retryable.
        // attempt 2: emit "partial" then error → must NOT retry (content already shown).
        AtomicInteger subs = new AtomicInteger();
        Flux<String> source = Flux.defer(() -> {
            int n = subs.incrementAndGet();
            if (n == 1) {
                return Flux.error(new RuntimeException("502 early"));
            }
            return Flux.concat(Flux.just("partial"),
                    Flux.error(new RuntimeException("502 mid-stream")));
        });

        List<String> collected = new ArrayList<>();
        assertThatThrownBy(() ->
                policy(true, 5, null).apply(source).doOnNext(collected::add).blockLast())
                .isInstanceOf(Exception.class);

        assertThat(subs.get()).isEqualTo(2);        // retried once, then stopped
        assertThat(collected).containsExactly("partial"); // no duplicate re-emit
    }

    @Test
    void disabled_bypassesRetryEntirely() {
        AtomicInteger subs = new AtomicInteger();
        Flux<String> source = Flux.defer(() -> {
            subs.incrementAndGet();
            return Flux.error(new RuntimeException("502 upstream_error"));
        });

        assertThatThrownBy(() -> policy(false, 10, null).apply(source).blockLast())
                .isInstanceOf(Exception.class);
        assertThat(subs.get()).isEqualTo(1); // disabled → no retry
    }

    @Test
    void success_passesThroughUnchanged() {
        Flux<String> source = Flux.just("a", "b", "c");
        List<String> out = policy(true, 3, null).apply(source).collectList().block();
        assertThat(out).containsExactly("a", "b", "c");
    }

    @Test
    void timeoutDisabled_slowSuccess_isNotRetriedNorInterrupted() {
        // Regression: a slow-but-healthy stream (large model, tools) must NOT be timed out and
        // retried. With per-attempt timeout disabled (0), the slow success passes through with a
        // single subscription — no false timeout, no re-subscription into a running agent.
        AtomicInteger subs = new AtomicInteger();
        Flux<String> slow = Flux.defer(() -> {
            subs.incrementAndGet();
            return Flux.just("ok").delayElements(Duration.ofMillis(50));
        });
        RetryPolicy noTimeout = new RetryPolicy(true, 5, Duration.ZERO, FAST, FAST,
                new TransientErrorClassifier(), null);

        List<String> out = noTimeout.apply(slow).collectList().block();

        assertThat(out).containsExactly("ok");
        assertThat(subs.get()).isEqualTo(1);
    }

    @Test
    void errorRetryStillWorks_whenTimeoutDisabled() {
        // Disabling the timeout must not disable transient-error retry (the real 502 use case).
        AtomicInteger subs = new AtomicInteger();
        Flux<String> source = Flux.defer(() -> {
            subs.incrementAndGet();
            return Flux.error(new RuntimeException("502 upstream_error"));
        });
        RetryPolicy noTimeout = new RetryPolicy(true, 3, Duration.ZERO, FAST, FAST,
                new TransientErrorClassifier(), null);

        assertThatThrownBy(() -> noTimeout.apply(source).blockLast()).isInstanceOf(Exception.class);
        assertThat(subs.get()).isEqualTo(4); // 1 + 3 retries, timeout off but error-retry on
    }

    @Test
    void retryListener_invokedPerRetry() {
        List<Long> retryLog = new ArrayList<>();
        Flux<String> source = Flux.error(new RuntimeException("503 unavailable"));

        assertThatThrownBy(() -> policy(true, 2, retryLog).apply(source).blockLast())
                .isInstanceOf(Exception.class);
        assertThat(retryLog).containsExactly(1L, 2L); // one callback per retry
    }
}
