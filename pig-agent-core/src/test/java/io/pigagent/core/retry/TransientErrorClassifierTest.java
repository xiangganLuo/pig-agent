package io.pigagent.core.retry;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.concurrent.TimeoutException;

import static org.assertj.core.api.Assertions.assertThat;

class TransientErrorClassifierTest {

    private final TransientErrorClassifier classifier = new TransientErrorClassifier();

    @Test
    void http5xx_isTransient() {
        assertThat(classifier.isTransient(
                new RuntimeException("Failed to stream Anthropic API: 502: upstream_error"))).isTrue();
        assertThat(classifier.isTransient(new RuntimeException("503 Service Unavailable"))).isTrue();
        assertThat(classifier.isTransient(new RuntimeException("500 internal error"))).isTrue();
    }

    @Test
    void http4xx_isNotTransient() {
        assertThat(classifier.isTransient(new RuntimeException("401 Unauthorized"))).isFalse();
        assertThat(classifier.isTransient(new RuntimeException("403 Forbidden"))).isFalse();
        assertThat(classifier.isTransient(new RuntimeException("400 Bad Request"))).isFalse();
    }

    @Test
    void timeout_isTransient() {
        assertThat(classifier.isTransient(new TimeoutException("timed out"))).isTrue();
        // also when wrapped as a cause
        assertThat(classifier.isTransient(
                new RuntimeException("wrapper", new TimeoutException("inner")))).isTrue();
    }

    @Test
    void ioException_isTransient() {
        assertThat(classifier.isTransient(new IOException("connection reset"))).isTrue();
        assertThat(classifier.isTransient(
                new RuntimeException("stream failed", new IOException("broken pipe")))).isTrue();
    }

    @Test
    void unknownError_isNotTransient_conservativeDefault() {
        assertThat(classifier.isTransient(new RuntimeException("something odd"))).isFalse();
        assertThat(classifier.isTransient(new IllegalStateException("no status here"))).isFalse();
    }

    @Test
    void fourXxWins_whenBothPresent() {
        // A client error is permanent even if some 5xx-looking token also appears.
        assertThat(classifier.isTransient(
                new RuntimeException("400 Bad Request (retried 500 times)"))).isFalse();
    }

    @Test
    void nullError_isNotTransient() {
        assertThat(classifier.isTransient(null)).isFalse();
    }
}
