package io.pigagent.channel.outreach;

import io.pigagent.core.outreach.Notification;
import io.pigagent.core.outreach.OutreachAction;

/**
 * Renders a {@link Notification} to plain text for a text channel (title, body, and an optional action
 * prompt). Pure and dependency-free so it is trivially unit-testable and reusable by any
 * {@link OutboundChannel}. Never includes the recipient (that is transport-addressing, not content).
 */
public final class NotificationRenderer {

    private NotificationRenderer() {
    }

    /** Render the notification as a compact text message (title line + body + optional action). */
    public static String render(Notification n) {
        if (n == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        if (!n.title().isBlank()) {
            sb.append(n.title().strip());
        }
        if (!n.body().isBlank()) {
            if (sb.length() > 0) {
                sb.append("\n\n");
            }
            sb.append(n.body().strip());
        }
        OutreachAction action = n.action();
        if (action != null && !action.prompt().isBlank()) {
            if (sb.length() > 0) {
                sb.append("\n\n");
            }
            sb.append(action.prompt().strip());
            if (!action.options().isEmpty()) {
                sb.append(" [").append(String.join(" / ", action.options())).append("]");
            }
        }
        return sb.toString();
    }
}
