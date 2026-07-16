package io.pigagent.core.outreach;

/**
 * Severity of a proactive outreach {@link Notification}. Drives the guardrail's urgent bypass:
 * {@link #URGENT} outreach bypasses quiet-hours and rate-limiting (a 24h assistant must be able to
 * raise a genuinely urgent alert), while de-dup still applies to everyone.
 */
public enum Severity {
    LOW,
    NORMAL,
    HIGH,
    URGENT;

    /** Whether this severity bypasses quiet-hours / rate-limiting in {@code OutreachGate}. */
    public boolean isUrgent() {
        return this == URGENT;
    }
}
