package io.pigagent.core.outreach;

import java.time.Duration;
import java.time.LocalTime;

/**
 * Immutable anti-nag policy for proactive outreach: the master enable flag plus the three guardrail
 * knobs — quiet-hours, rate-limit, and de-dup. Pure value object; the stateful decisions live in
 * {@link OutreachGate}. All fields are fault-tolerantly normalized (a negative/zero window or count
 * degrades to a safe default) so a malformed config never produces a nonsensical policy.
 */
public record OutreachPolicy(
        boolean enabled,
        QuietHours quietHours,
        int maxPerWindow,     // <=0 → unlimited (no rate limit)
        Duration rateWindow,
        Duration dedupWindow  // <=0 → de-dup disabled
) {

    public OutreachPolicy {
        quietHours = quietHours == null ? QuietHours.disabled() : quietHours;
        if (maxPerWindow < 0) {
            maxPerWindow = 0;
        }
        rateWindow = (rateWindow == null || rateWindow.isNegative() || rateWindow.isZero())
                ? Duration.ofHours(1) : rateWindow;
        dedupWindow = (dedupWindow == null || dedupWindow.isNegative()) ? Duration.ZERO : dedupWindow;
    }

    /** A fully-off policy: nothing is ever sent. */
    public static OutreachPolicy disabled() {
        return new OutreachPolicy(false, QuietHours.disabled(), 0, Duration.ofHours(1), Duration.ZERO);
    }

    /** Whether a rate limit is in effect (a positive per-window cap). */
    public boolean rateLimited() {
        return maxPerWindow > 0;
    }

    /** Whether de-dup is in effect (a positive de-dup window). */
    public boolean dedupEnabled() {
        return !dedupWindow.isZero();
    }

    /**
     * A quiet-hours window during which non-urgent outreach is suppressed. {@link #isQuiet} handles a
     * window that wraps past midnight (e.g. 22:00–08:00). A zero-length window (start == end) is
     * treated as "never quiet".
     */
    public record QuietHours(boolean enabled, LocalTime start, LocalTime end) {

        public static QuietHours disabled() {
            return new QuietHours(false, LocalTime.MIDNIGHT, LocalTime.MIDNIGHT);
        }

        /** Whether {@code now} falls inside the quiet window (half-open [start, end), wrap-aware). */
        public boolean isQuiet(LocalTime now) {
            if (!enabled || now == null || start == null || end == null || start.equals(end)) {
                return false;
            }
            if (start.isBefore(end)) {
                return !now.isBefore(start) && now.isBefore(end);
            }
            // wrap-around midnight: quiet if now >= start OR now < end
            return !now.isBefore(start) || now.isBefore(end);
        }
    }
}
