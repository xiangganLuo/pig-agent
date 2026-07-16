package io.pigagent.tool.notify;

import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.ToolParam;
import io.pigagent.core.outreach.Notification;
import io.pigagent.core.outreach.NotificationResult;
import io.pigagent.core.outreach.NotificationService;
import io.pigagent.core.outreach.NotificationType;
import io.pigagent.core.outreach.Severity;
import io.pigagent.tool.availability.Availability;
import io.pigagent.tool.availability.ToolAvailability;
import io.pigagent.tool.contract.ToolErrors;

import java.util.Objects;
import java.util.Set;

/**
 * Lets the agent <b>proactively</b> notify the user through a configured outreach channel — the
 * agent-initiated outreach trigger. Classified {@code NETWORK} in {@code ToolRiskClassifier} (so it is
 * governed by the permission system) and gated by {@link ToolAvailability}: when outreach is disabled
 * the tool is hidden from the model schema entirely (zero behavior change when off).
 *
 * <p>Return contract: a delivered send returns a success line; a guardrail suppression returns a plain
 * status line (NOT a {@code {"error"}}, so the model doesn't retry-loop); a real failure (no channel /
 * transport error) returns the canonical {@code {"error":"<reason>"}}. The recipient token is NEVER
 * echoed — the tool builds the notification with the configured default target and never surfaces it.
 */
public final class NotifyUserTool implements ToolAvailability {

    static final String TOOL_NAME = "notifyUser";

    private final NotificationService service;
    private final java.util.function.BooleanSupplier enabled;

    public NotifyUserTool(NotificationService service, java.util.function.BooleanSupplier enabled) {
        this.service = Objects.requireNonNull(service, "service");
        this.enabled = enabled == null ? () -> false : enabled;
    }

    @Tool(description = "Proactively send a short notification/message to the user through a configured "
            + "outreach channel (e.g. a daily briefing, a reminder, or something that needs their "
            + "attention). Use sparingly and only when the user should be actively informed. Set urgent "
            + "to true only for time-critical alerts.")
    public String notifyUser(
            @ToolParam(name = "title", description = "Short notification title") String title,
            @ToolParam(name = "message", description = "The notification body / message text") String message,
            @ToolParam(name = "urgent", description = "true for a time-critical alert, otherwise false")
            String urgent) {
        try {
            boolean isUrgent = parseUrgent(urgent);
            Notification notification = Notification.of(
                    isUrgent ? NotificationType.ALERT : NotificationType.MESSAGE,
                    isUrgent ? Severity.URGENT : Severity.NORMAL,
                    title, message);
            NotificationResult result = service.notify(notification);
            return describe(result);
        } catch (Exception e) {
            return ToolErrors.message("notifyUser failed: " + e.getClass().getSimpleName());
        }
    }

    /** Map the service result to the tool's return contract. */
    private static String describe(NotificationResult result) {
        return switch (result.outcome()) {
            case DELIVERED -> "Notification sent to the user.";
            case DISABLED, QUIET_HOURS, RATE_LIMITED, DUPLICATE ->
                    "Not sent (guardrail: " + result.outcome().name().toLowerCase() + "). "
                            + "The user was not notified this time.";
            // NO_CHANNEL / FAILED → a real failure; detail names the channel id only, never a recipient.
            default -> ToolErrors.message(result.detail());
        };
    }

    private static boolean parseUrgent(String urgent) {
        if (urgent == null) {
            return false;
        }
        String s = urgent.strip().toLowerCase();
        return s.equals("true") || s.equals("yes") || s.equals("1");
    }

    @Override
    public Set<String> availabilityToolNames() {
        return Set.of(TOOL_NAME);
    }

    @Override
    public Availability checkAvailability() {
        return enabled.getAsBoolean()
                ? Availability.AVAILABLE
                : Availability.unavailable("outreach disabled (set outreach.enabled)");
    }
}
