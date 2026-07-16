package io.pigagent.core.outreach;

import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;

/** Rate-limit / de-dup / quiet-hours gating with a controllable clock (deterministic, offline). */
class OutreachGateTest {

    /** A test clock whose instant can be advanced. */
    private static final class MutableClock extends Clock {
        private Instant instant;
        private final ZoneId zone;

        MutableClock(Instant instant, ZoneId zone) {
            this.instant = instant;
            this.zone = zone;
        }

        void advance(Duration d) {
            instant = instant.plus(d);
        }

        @Override public ZoneId getZone() { return zone; }
        @Override public Clock withZone(ZoneId z) { return new MutableClock(instant, z); }
        @Override public Instant instant() { return instant; }
    }

    private static Notification msg(String title, String body, Severity sev) {
        return Notification.of(NotificationType.MESSAGE, sev, title, body);
    }

    @Test
    void disabledPolicyReturnsDisabled() {
        OutreachGate gate = new OutreachGate(OutreachPolicy.disabled());
        assertThat(gate.evaluate(msg("t", "b", Severity.NORMAL))).isEqualTo(OutreachDecision.DISABLED);
    }

    @Test
    void normalNotificationAllowed() {
        OutreachPolicy p = new OutreachPolicy(true, OutreachPolicy.QuietHours.disabled(),
                0, Duration.ofHours(1), Duration.ZERO);
        OutreachGate gate = new OutreachGate(p);
        assertThat(gate.evaluate(msg("t", "b", Severity.NORMAL))).isEqualTo(OutreachDecision.ALLOW);
    }

    @Test
    void duplicateWithinWindowSuppressed() {
        MutableClock clock = new MutableClock(Instant.parse("2026-07-16T12:00:00Z"), ZoneOffset.UTC);
        OutreachPolicy p = new OutreachPolicy(true, OutreachPolicy.QuietHours.disabled(),
                0, Duration.ofHours(1), Duration.ofMinutes(30));
        OutreachGate gate = new OutreachGate(() -> p, clock);

        assertThat(gate.evaluate(msg("a", "b", Severity.NORMAL))).isEqualTo(OutreachDecision.ALLOW);
        assertThat(gate.evaluate(msg("a", "b", Severity.NORMAL))).isEqualTo(OutreachDecision.DUPLICATE);

        clock.advance(Duration.ofMinutes(31));
        assertThat(gate.evaluate(msg("a", "b", Severity.NORMAL))).isEqualTo(OutreachDecision.ALLOW);
    }

    @Test
    void dedupAppliesToUrgentToo() {
        OutreachPolicy p = new OutreachPolicy(true, OutreachPolicy.QuietHours.disabled(),
                0, Duration.ofHours(1), Duration.ofMinutes(30));
        OutreachGate gate = new OutreachGate(p);

        assertThat(gate.evaluate(msg("a", "b", Severity.URGENT))).isEqualTo(OutreachDecision.ALLOW);
        assertThat(gate.evaluate(msg("a", "b", Severity.URGENT))).isEqualTo(OutreachDecision.DUPLICATE);
    }

    @Test
    void quietHoursSuppressNonUrgentButUrgentBypasses() {
        // 23:00 UTC falls inside the 22:00–08:00 quiet window.
        Clock clock = Clock.fixed(Instant.parse("2026-07-16T23:00:00Z"), ZoneOffset.UTC);
        OutreachPolicy p = new OutreachPolicy(true,
                new OutreachPolicy.QuietHours(true, LocalTime.of(22, 0), LocalTime.of(8, 0)),
                0, Duration.ofHours(1), Duration.ZERO);
        OutreachGate gate = new OutreachGate(() -> p, clock);

        assertThat(gate.evaluate(msg("t", "b", Severity.NORMAL))).isEqualTo(OutreachDecision.QUIET_HOURS);
        assertThat(gate.evaluate(msg("t2", "b2", Severity.URGENT))).isEqualTo(OutreachDecision.ALLOW);
    }

    @Test
    void rateLimitSuppressesNonUrgentButUrgentBypasses() {
        OutreachPolicy p = new OutreachPolicy(true, OutreachPolicy.QuietHours.disabled(),
                2, Duration.ofHours(1), Duration.ZERO);
        OutreachGate gate = new OutreachGate(p);

        assertThat(gate.evaluate(msg("t1", "1", Severity.NORMAL))).isEqualTo(OutreachDecision.ALLOW);
        assertThat(gate.evaluate(msg("t2", "2", Severity.NORMAL))).isEqualTo(OutreachDecision.ALLOW);
        assertThat(gate.evaluate(msg("t3", "3", Severity.NORMAL))).isEqualTo(OutreachDecision.RATE_LIMITED);
        // Urgent bypasses the rate cap.
        assertThat(gate.evaluate(msg("t4", "4", Severity.URGENT))).isEqualTo(OutreachDecision.ALLOW);
    }

    @Test
    void rateWindowSlidesAndAllowsAgain() {
        MutableClock clock = new MutableClock(Instant.parse("2026-07-16T12:00:00Z"), ZoneOffset.UTC);
        OutreachPolicy p = new OutreachPolicy(true, OutreachPolicy.QuietHours.disabled(),
                1, Duration.ofHours(1), Duration.ZERO);
        OutreachGate gate = new OutreachGate(() -> p, clock);

        assertThat(gate.evaluate(msg("t1", "1", Severity.NORMAL))).isEqualTo(OutreachDecision.ALLOW);
        assertThat(gate.evaluate(msg("t2", "2", Severity.NORMAL))).isEqualTo(OutreachDecision.RATE_LIMITED);

        clock.advance(Duration.ofMinutes(61));
        assertThat(gate.evaluate(msg("t3", "3", Severity.NORMAL))).isEqualTo(OutreachDecision.ALLOW);
    }
}
