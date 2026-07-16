package io.pigagent.channel.outreach;

import io.pigagent.core.outreach.Notification;
import io.pigagent.core.outreach.NotificationResult;
import io.pigagent.core.outreach.NotificationService;
import io.pigagent.core.outreach.NotificationType;
import io.pigagent.core.outreach.Severity;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** {@link ScheduledOutreach} arms with the right id/cron and, when fired, calls notify. */
class ScheduledOutreachTest {

    /** Captures the last scheduled (id, cron, action) so a test can run the action directly. */
    private static final class CapturingScheduler implements OutreachScheduler {
        String id;
        String cron;
        Runnable action;

        @Override
        public void schedule(String id, String cron, Runnable action) {
            this.id = id;
            this.cron = cron;
            this.action = action;
        }
    }

    private static final class RecordingService implements NotificationService {
        final List<Notification> notified = new ArrayList<>();

        @Override
        public NotificationResult notify(Notification notification) {
            notified.add(notification);
            return NotificationResult.delivered("ok");
        }
    }

    @Test
    void armRegistersWithIdAndCron() {
        CapturingScheduler scheduler = new CapturingScheduler();
        ScheduledOutreach outreach = new ScheduledOutreach("outreach:briefing", "0 9 * * *",
                new RecordingService(),
                () -> Notification.of(NotificationType.BRIEFING, Severity.NORMAL, "简报", "内容"));

        outreach.arm(scheduler);

        assertThat(scheduler.id).isEqualTo("outreach:briefing");
        assertThat(scheduler.cron).isEqualTo("0 9 * * *");
        assertThat(scheduler.action).isNotNull();
    }

    @Test
    void firingScheduledActionCallsNotifyWithSuppliedNotification() {
        CapturingScheduler scheduler = new CapturingScheduler();
        RecordingService service = new RecordingService();
        ScheduledOutreach outreach = new ScheduledOutreach("outreach:briefing", "0 9 * * *", service,
                () -> Notification.of(NotificationType.BRIEFING, Severity.NORMAL, "简报", "内容"));
        outreach.arm(scheduler);

        scheduler.action.run(); // simulate the scheduler firing at cron time

        assertThat(service.notified).hasSize(1);
        assertThat(service.notified.get(0).type()).isEqualTo(NotificationType.BRIEFING);
        assertThat(service.notified.get(0).title()).isEqualTo("简报");
    }

    @Test
    void fireSwallowsSupplierException() {
        CapturingScheduler scheduler = new CapturingScheduler();
        ScheduledOutreach outreach = new ScheduledOutreach("id", "0 9 * * *", new RecordingService(),
                () -> {
                    throw new RuntimeException("compose failed");
                });
        outreach.arm(scheduler);

        // Must not throw out of the scheduled run.
        scheduler.action.run();
    }
}
