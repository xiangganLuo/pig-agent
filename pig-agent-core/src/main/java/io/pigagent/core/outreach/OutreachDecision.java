package io.pigagent.core.outreach;

/**
 * The verdict of {@link OutreachGate#evaluate}: whether a notification may be sent now, and if not,
 * which guardrail suppressed it.
 */
public enum OutreachDecision {
    /** Send it. */
    ALLOW,
    /** Outreach is disabled. */
    DISABLED,
    /** Non-urgent, and currently inside the quiet-hours window. */
    QUIET_HOURS,
    /** Non-urgent, and the rate-limit window is full. */
    RATE_LIMITED,
    /** A same-keyed notification was already sent within the de-dup window. */
    DUPLICATE;

    public boolean allowed() {
        return this == ALLOW;
    }
}
