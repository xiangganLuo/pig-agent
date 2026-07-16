package io.pigagent.core.outreach;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalTime;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.function.Supplier;

/**
 * The anti-nag gate: the single home of the rate-limit + de-dup + quiet-hours logic that keeps a
 * 24h assistant from spamming. Stateful (it remembers recent sends), thread-safe (one lock), and
 * clock-injectable so the time-based rules are deterministically unit-testable.
 *
 * <p>Order of checks in {@link #evaluate}:
 * <ol>
 *   <li>disabled → {@link OutreachDecision#DISABLED};</li>
 *   <li>de-dup (applies to <em>all</em> messages, urgent included) → {@link OutreachDecision#DUPLICATE};</li>
 *   <li>for non-urgent only: quiet-hours → {@link OutreachDecision#QUIET_HOURS}, then rate-limit →
 *       {@link OutreachDecision#RATE_LIMITED};</li>
 *   <li>otherwise record the send and {@link OutreachDecision#ALLOW}.</li>
 * </ol>
 * <b>Urgent bypass (deliberate):</b> URGENT severity bypasses quiet-hours and rate-limiting so a
 * genuinely urgent alert always gets through; de-dup still applies (a truly new urgent alert has a
 * different key). Allowed urgent sends are still counted toward the rate window (flood protection).
 */
public final class OutreachGate {

    private final Supplier<OutreachPolicy> policy;
    private final Clock clock;
    private final Deque<Instant> recentAllowed = new ArrayDeque<>();
    private final Map<String, Instant> lastByKey = new HashMap<>();

    public OutreachGate(Supplier<OutreachPolicy> policy, Clock clock) {
        this.policy = Objects.requireNonNull(policy, "policy");
        this.clock = clock == null ? Clock.systemDefaultZone() : clock;
    }

    /** Convenience for a fixed policy on the system clock. */
    public OutreachGate(OutreachPolicy policy) {
        this(() -> policy == null ? OutreachPolicy.disabled() : policy, Clock.systemDefaultZone());
    }

    /** Evaluate + (on ALLOW) record one notification. */
    public synchronized OutreachDecision evaluate(Notification n) {
        OutreachPolicy p = policy.get();
        if (p == null || !p.enabled()) {
            return OutreachDecision.DISABLED;
        }
        Instant now = clock.instant();
        boolean urgent = n != null && n.urgent();
        String key = n == null ? "" : n.effectiveDedupKey();

        if (p.dedupEnabled()) {
            Instant last = lastByKey.get(key);
            if (last != null && Duration.between(last, now).compareTo(p.dedupWindow()) < 0) {
                return OutreachDecision.DUPLICATE;
            }
        }
        if (!urgent) {
            if (p.quietHours().enabled() && p.quietHours().isQuiet(LocalTime.now(clock))) {
                return OutreachDecision.QUIET_HOURS;
            }
            if (p.rateLimited()) {
                prune(now, p.rateWindow());
                if (recentAllowed.size() >= p.maxPerWindow()) {
                    return OutreachDecision.RATE_LIMITED;
                }
            }
        }
        lastByKey.put(key, now);
        recentAllowed.addLast(now);
        prune(now, p.rateWindow());
        return OutreachDecision.ALLOW;
    }

    /** Drop rate-window entries older than {@code now - window} (window is the half-open (cutoff, now]). */
    private void prune(Instant now, Duration window) {
        Instant cutoff = now.minus(window);
        while (!recentAllowed.isEmpty() && !recentAllowed.peekFirst().isAfter(cutoff)) {
            recentAllowed.pollFirst();
        }
    }
}
