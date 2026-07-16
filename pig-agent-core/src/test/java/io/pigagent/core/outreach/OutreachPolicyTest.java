package io.pigagent.core.outreach;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.LocalTime;

import static org.assertj.core.api.Assertions.assertThat;

/** {@link OutreachPolicy} normalization + quiet-hours window logic (including wrap-around midnight). */
class OutreachPolicyTest {

    @Test
    void disabledPolicyIsOff() {
        OutreachPolicy p = OutreachPolicy.disabled();
        assertThat(p.enabled()).isFalse();
        assertThat(p.rateLimited()).isFalse();
        assertThat(p.dedupEnabled()).isFalse();
    }

    @Test
    void negativeCountAndWindowNormalizedToSafeDefaults() {
        OutreachPolicy p = new OutreachPolicy(true, OutreachPolicy.QuietHours.disabled(),
                -5, Duration.ofSeconds(-1), Duration.ofSeconds(-10));
        assertThat(p.maxPerWindow()).isZero();                 // <0 → unlimited
        assertThat(p.rateWindow()).isEqualTo(Duration.ofHours(1)); // invalid → 1h default
        assertThat(p.dedupWindow()).isEqualTo(Duration.ZERO);  // <0 → disabled
        assertThat(p.rateLimited()).isFalse();
        assertThat(p.dedupEnabled()).isFalse();
    }

    @Test
    void quietHoursSameDayWindow() {
        OutreachPolicy.QuietHours qh = new OutreachPolicy.QuietHours(
                true, LocalTime.of(9, 0), LocalTime.of(17, 0));
        assertThat(qh.isQuiet(LocalTime.of(12, 0))).isTrue();
        assertThat(qh.isQuiet(LocalTime.of(9, 0))).isTrue();   // inclusive start
        assertThat(qh.isQuiet(LocalTime.of(17, 0))).isFalse(); // exclusive end
        assertThat(qh.isQuiet(LocalTime.of(8, 59))).isFalse();
    }

    @Test
    void quietHoursWrapAroundMidnight() {
        OutreachPolicy.QuietHours qh = new OutreachPolicy.QuietHours(
                true, LocalTime.of(22, 0), LocalTime.of(8, 0));
        assertThat(qh.isQuiet(LocalTime.of(23, 30))).isTrue(); // before midnight
        assertThat(qh.isQuiet(LocalTime.of(2, 0))).isTrue();   // after midnight
        assertThat(qh.isQuiet(LocalTime.of(12, 0))).isFalse(); // daytime
        assertThat(qh.isQuiet(LocalTime.of(8, 0))).isFalse();  // exclusive end
    }

    @Test
    void disabledOrZeroLengthQuietHoursNeverQuiet() {
        assertThat(OutreachPolicy.QuietHours.disabled().isQuiet(LocalTime.NOON)).isFalse();
        OutreachPolicy.QuietHours zero = new OutreachPolicy.QuietHours(
                true, LocalTime.of(9, 0), LocalTime.of(9, 0));
        assertThat(zero.isQuiet(LocalTime.of(9, 0))).isFalse();
    }
}
