package io.pigagent.core.outreach;

/**
 * Immutable outcome of a {@link NotificationService#notify} call. Never an exception: routing +
 * guardrail + transport all fold into one of the {@link Outcome} values plus a short, credential-safe
 * {@code detail} (which MUST NOT contain a recipient token).
 */
public record NotificationResult(Outcome outcome, String detail) {

    public enum Outcome {
        /** Handed to the channel's outbound transport successfully. */
        DELIVERED,
        /** Outreach is disabled — nothing sent (no-op). */
        DISABLED,
        /** Suppressed by quiet-hours (non-urgent). */
        QUIET_HOURS,
        /** Suppressed by the rate limiter (non-urgent). */
        RATE_LIMITED,
        /** Suppressed as a duplicate within the de-dup window. */
        DUPLICATE,
        /** No outbound channel is available for the resolved target. */
        NO_CHANNEL,
        /** The outbound transport reported failure (or threw). */
        FAILED
    }

    public NotificationResult {
        detail = detail == null ? "" : detail;
    }

    /** Whether the notification reached the channel transport. */
    public boolean delivered() {
        return outcome == Outcome.DELIVERED;
    }

    /** Whether a guardrail suppressed the notification (disabled / quiet-hours / rate / duplicate). */
    public boolean suppressed() {
        return outcome == Outcome.DISABLED || outcome == Outcome.QUIET_HOURS
                || outcome == Outcome.RATE_LIMITED || outcome == Outcome.DUPLICATE;
    }

    public static NotificationResult of(Outcome outcome, String detail) {
        return new NotificationResult(outcome, detail);
    }

    public static NotificationResult delivered(String detail) {
        return new NotificationResult(Outcome.DELIVERED, detail);
    }
}
