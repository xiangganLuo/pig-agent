package io.pigagent.core.outreach;

/**
 * The kind of a proactive outreach {@link Notification}. Purely descriptive metadata (used for
 * rendering / classification); it does not by itself change routing or guardrail behaviour — that is
 * driven by {@link Severity} (urgency) and the target channel/recipient.
 */
public enum NotificationType {
    /** A scheduled digest / daily briefing. */
    BRIEFING,
    /** A time-based nudge (e.g. an upcoming task). */
    REMINDER,
    /** A digital-employee morning report pushed to a channel. */
    REPORT,
    /** An anomaly / something that needs attention. */
    ALERT,
    /** A request for the user to decide (e.g. a yes/no). */
    ACTION_REQUEST,
    /** A generic proactive message. */
    MESSAGE
}
