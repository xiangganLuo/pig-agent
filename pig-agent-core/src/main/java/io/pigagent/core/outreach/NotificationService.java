package io.pigagent.core.outreach;

/**
 * The framework-neutral seam for proactive outreach: route a {@link Notification} to the right
 * outbound channel, after the anti-nag guardrails. The actual send lives behind this seam (a channel
 * implementation), so callers (the {@code notifyUser} tool, the {@code /notify} command, scheduled
 * triggers, the morning-report push) depend only on this interface and are fully unit-testable with a
 * mock.
 *
 * <p>This interface is deliberately transport-agnostic. In a future move to a native channel gateway
 * the implementation is swapped (its {@code send} seam re-pointed) while this contract and the
 * outreach logic (routing, de-dup, guardrails, triggers) stay unchanged.
 */
public interface NotificationService {

    /** Route + deliver (or suppress) a notification. Never throws; always returns a result. */
    NotificationResult notify(Notification notification);
}
