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
    void retryListener_invokedPerRetry() {
        List<Long> retryLog = new ArrayList<>();
        Flux<String> source = Flux.error(new RuntimeException("503 unavailable"));

        assertThatThrownBy(() -> policy(true, 2, retryLog).apply(source).blockLast())
                .isInstanceOf(Exception.class);
        assertThat(retryLog).containsExactly(1L, 2L); // one callback per retry
    }
}
