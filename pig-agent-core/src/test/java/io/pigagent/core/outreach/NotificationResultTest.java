package io.pigagent.core.outreach;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** {@link NotificationResult} outcome helpers. */
class NotificationResultTest {

    @Test
    void deliveredIsDeliveredNotSuppressed() {
        NotificationResult r = NotificationResult.delivered("sent via feishu");
        assertThat(r.delivered()).isTrue();
        assertThat(r.suppressed()).isFalse();
    }

    @Test
    void guardrailOutcomesAreSuppressed() {
        assertThat(NotificationResult.of(NotificationResult.Outcome.DISABLED, "").suppressed()).isTrue();
        assertThat(NotificationResult.of(NotificationResult.Outcome.QUIET_HOURS, "").suppressed()).isTrue();
        assertThat(NotificationResult.of(NotificationResult.Outcome.RATE_LIMITED, "").suppressed()).isTrue();
        assertThat(NotificationResult.of(NotificationResult.Outcome.DUPLICATE, "").suppressed()).isTrue();
    }

    @Test
    void failureOutcomesAreNeitherDeliveredNorSuppressed() {
        NotificationResult noChannel = NotificationResult.of(NotificationResult.Outcome.NO_CHANNEL, "x");
        NotificationResult failed = NotificationResult.of(NotificationResult.Outcome.FAILED, "y");
        assertThat(noChannel.delivered()).isFalse();
        assertThat(noChannel.suppressed()).isFalse();
        assertThat(failed.delivered()).isFalse();
        assertThat(failed.suppressed()).isFalse();
    }

    @Test
    void nullDetailNormalizedToEmpty() {
        assertThat(NotificationResult.of(NotificationResult.Outcome.FAILED, null).detail()).isEmpty();
    }
}
