package io.pigagent.channel.outreach;

import io.pigagent.core.outreach.Notification;
import io.pigagent.core.outreach.NotificationService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Objects;
import java.util.function.Supplier;

/**
 * A <b>scheduled</b> outreach trigger (Strategy): at a cron time, build a {@link Notification} (via a
 * supplier, so its content can be freshly composed each run) and hand it to the
 * {@link NotificationService}. Typical use: push a daily briefing at 09:00.
 *
 * <p>Armed against a {@link OutreachScheduler} seam (the CLI backs it with the real
 * {@code TaskScheduler}); {@link #fire()} is package-visible so a test can drive it directly.
 */
public final class ScheduledOutreach {

    private static final Logger log = LoggerFactory.getLogger(ScheduledOutreach.class);

    private final String id;
    private final String cron;
    private final NotificationService service;
    private final Supplier<Notification> notificationSupplier;

    public ScheduledOutreach(String id, String cron, NotificationService service,
                             Supplier<Notification> notificationSupplier) {
        this.id = Objects.requireNonNull(id, "id");
        this.cron = Objects.requireNonNull(cron, "cron");
        this.service = Objects.requireNonNull(service, "service");
        this.notificationSupplier = Objects.requireNonNull(notificationSupplier, "notificationSupplier");
    }

    /** Register this trigger with the scheduler. */
    public void arm(OutreachScheduler scheduler) {
        scheduler.schedule(id, cron, this::fire);
        log.info("Scheduled outreach '{}' armed [{}]", id, cron);
    }

    /** Compose + send the notification now. Fault-tolerant (never throws out of a scheduled run). */
    void fire() {
        try {
            service.notify(notificationSupplier.get());
        } catch (Exception e) {
            log.warn("Scheduled outreach '{}' failed: {}", id, e.getMessage());
        }
    }
}
