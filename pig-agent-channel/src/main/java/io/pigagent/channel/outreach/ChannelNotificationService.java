package io.pigagent.channel.outreach;

import io.pigagent.core.outreach.Notification;
import io.pigagent.core.outreach.NotificationResult;
import io.pigagent.core.outreach.NotificationService;
import io.pigagent.core.outreach.OutreachDecision;
import io.pigagent.core.outreach.OutreachGate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Objects;
import java.util.Optional;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * Routes a {@link Notification} to the right {@link OutboundChannel} after the anti-nag
 * {@link OutreachGate}. This is the concrete {@link NotificationService} the assistant uses to reach
 * out.
 *
 * <p>Flow of {@link #notify}:
 * <ol>
 *   <li>{@link OutreachGate#evaluate} — a non-{@code ALLOW} verdict short-circuits (no lookup, no
 *       send), mapping to a {@code DISABLED}/{@code QUIET_HOURS}/{@code RATE_LIMITED}/{@code DUPLICATE}
 *       result (so a disabled config is a true no-op);</li>
 *   <li>resolve the target channel + recipient (blank → configured default);</li>
 *   <li>look up the {@link OutboundChannel} for that channel id — missing → a helpful
 *       {@code NO_CHANNEL} result (naming the channel id, never a credential);</li>
 *   <li>send — {@code true} → {@code DELIVERED}, {@code false}/throw → {@code FAILED}.</li>
 * </ol>
 *
 * <p>Credential hygiene: the recipient token is NEVER logged (logs carry only channel id / type /
 * severity / decision). Failure details are surfaced but must not contain a recipient.
 */
public final class ChannelNotificationService implements NotificationService {

    private static final Logger log = LoggerFactory.getLogger(ChannelNotificationService.class);

    private final Function<String, Optional<OutboundChannel>> channelLookup;
    private final OutreachGate gate;
    private final Supplier<String> defaultChannel;
    private final Supplier<String> defaultRecipient;

    public ChannelNotificationService(Function<String, Optional<OutboundChannel>> channelLookup,
                                      OutreachGate gate,
                                      Supplier<String> defaultChannel,
                                      Supplier<String> defaultRecipient) {
        this.channelLookup = Objects.requireNonNull(channelLookup, "channelLookup");
        this.gate = Objects.requireNonNull(gate, "gate");
        this.defaultChannel = defaultChannel == null ? () -> null : defaultChannel;
        this.defaultRecipient = defaultRecipient == null ? () -> null : defaultRecipient;
    }

    @Override
    public NotificationResult notify(Notification notification) {
        if (notification == null) {
            return NotificationResult.of(NotificationResult.Outcome.FAILED, "null notification");
        }
        OutreachDecision decision = gate.evaluate(notification);
        if (!decision.allowed()) {
            log.debug("Outreach suppressed [{}] type={} severity={}",
                    decision, notification.type(), notification.severity());
            return suppressedResult(decision);
        }

        String channelId = firstNonBlank(notification.targetChannel(), defaultChannel.get());
        String recipient = firstNonBlank(notification.recipient(), defaultRecipient.get());
        if (channelId == null) {
            return NotificationResult.of(NotificationResult.Outcome.NO_CHANNEL,
                    "no target channel configured (set outreach.channel)");
        }

        Optional<OutboundChannel> channel = channelLookup.apply(channelId);
        if (channel.isEmpty()) {
            return NotificationResult.of(NotificationResult.Outcome.NO_CHANNEL,
                    "no outbound channel '" + channelId + "' available");
        }

        try {
            boolean ok = channel.get().send(recipient, notification);
            log.info("Outreach {} via '{}' type={} severity={}",
                    ok ? "delivered" : "failed", channelId, notification.type(), notification.severity());
            return ok
                    ? NotificationResult.delivered("sent via " + channelId)
                    : NotificationResult.of(NotificationResult.Outcome.FAILED,
                            "channel '" + channelId + "' reported failure");
        } catch (Exception e) {
            log.warn("Outreach send via '{}' threw: {}", channelId, e.getClass().getSimpleName());
            return NotificationResult.of(NotificationResult.Outcome.FAILED,
                    "channel '" + channelId + "' error: " + e.getClass().getSimpleName());
        }
    }

    private static NotificationResult suppressedResult(OutreachDecision decision) {
        NotificationResult.Outcome outcome = switch (decision) {
            case DISABLED -> NotificationResult.Outcome.DISABLED;
            case QUIET_HOURS -> NotificationResult.Outcome.QUIET_HOURS;
            case RATE_LIMITED -> NotificationResult.Outcome.RATE_LIMITED;
            case DUPLICATE -> NotificationResult.Outcome.DUPLICATE;
            default -> NotificationResult.Outcome.FAILED;
        };
        return NotificationResult.of(outcome, decision.name().toLowerCase());
    }

    private static String firstNonBlank(String a, String b) {
        if (a != null && !a.isBlank()) {
            return a;
        }
        return (b != null && !b.isBlank()) ? b : null;
    }
}
