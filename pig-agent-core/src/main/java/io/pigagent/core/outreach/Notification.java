package io.pigagent.core.outreach;

/**
 * An immutable proactive-outreach message the assistant sends <em>unsolicited</em> to a user through a
 * channel (a daily briefing, a reminder, a report, an alert, or a decision request) — as opposed to a
 * reply in an ongoing conversation.
 *
 * <p>Framework-neutral value type (pure JDK): it names <em>what</em> to say and (optionally) where,
 * but knows nothing about how it is transported. {@code targetChannel}/{@code recipient} may be blank,
 * meaning "use the configured default"; {@code dedupKey} may be blank, meaning "derive it from the
 * title + body". Null fields are normalized to safe defaults so a partially-built notification is
 * always well-formed.
 */
public record Notification(
        NotificationType type,
        Severity severity,
        String title,
        String body,
        OutreachAction action,   // nullable: no attached action
        String targetChannel,    // nullable/blank: use default channel
        String recipient,        // nullable/blank: use default recipient
        String dedupKey          // nullable/blank: derive from title+body
) {

    public Notification {
        type = type == null ? NotificationType.MESSAGE : type;
        severity = severity == null ? Severity.NORMAL : severity;
        title = title == null ? "" : title;
        body = body == null ? "" : body;
        targetChannel = blankToNull(targetChannel);
        recipient = blankToNull(recipient);
        dedupKey = blankToNull(dedupKey);
    }

    /** A minimal notification with no target/action/dedup override (defaults resolved downstream). */
    public static Notification of(NotificationType type, Severity severity, String title, String body) {
        return new Notification(type, severity, title, body, null, null, null, null);
    }

    /** Whether this notification bypasses quiet-hours / rate-limiting (its severity is URGENT). */
    public boolean urgent() {
        return severity.isUrgent();
    }

    /** The de-dup key: the explicit one if set, otherwise derived from {@code title + body}. */
    public String effectiveDedupKey() {
        return dedupKey != null ? dedupKey : (title + "|" + body);
    }

    /** Copy with a specific target channel + recipient. */
    public Notification withTarget(String targetChannel, String recipient) {
        return new Notification(type, severity, title, body, action, targetChannel, recipient, dedupKey);
    }

    /** Copy with an attached action. */
    public Notification withAction(OutreachAction action) {
        return new Notification(type, severity, title, body, action, targetChannel, recipient, dedupKey);
    }

    /** Copy with an explicit de-dup key. */
    public Notification withDedupKey(String dedupKey) {
        return new Notification(type, severity, title, body, action, targetChannel, recipient, dedupKey);
    }

    private static String blankToNull(String s) {
        return (s == null || s.isBlank()) ? null : s;
    }
}
