package io.pigagent.channel.outreach;

import io.pigagent.core.agent.AgentSpec;
import io.pigagent.core.agent.runner.AgentReport;
import io.pigagent.core.outreach.Notification;
import io.pigagent.core.outreach.NotificationResult;
import io.pigagent.core.outreach.NotificationService;
import io.pigagent.core.outreach.NotificationType;
import io.pigagent.core.outreach.Severity;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** {@link NotificationReportWriter} maps a morning report to a REPORT notification and pushes it. */
class NotificationReportWriterTest {

    private static final class RecordingService implements NotificationService {
        Notification last;
        boolean throwOnNotify;

        @Override
        public NotificationResult notify(Notification notification) {
            if (throwOnNotify) {
                throw new RuntimeException("push failed");
            }
            last = notification;
            return NotificationResult.delivered("ok");
        }
    }

    @Test
    void pushesReportAsNotification() {
        RecordingService service = new RecordingService();
        NotificationReportWriter writer = new NotificationReportWriter(service);
        AgentReport report = new AgentReport("nightwatch", "Nightwatch",
                AgentReport.Outcome.SUCCESS, "checked the build", List.of(), "");

        writer.write(AgentSpec.create("nightwatch", "Nightwatch"), report);

        assertThat(service.last).isNotNull();
        assertThat(service.last.type()).isEqualTo(NotificationType.REPORT);
        assertThat(service.last.severity()).isEqualTo(Severity.NORMAL);
        assertThat(service.last.title()).contains("Nightwatch");
        assertThat(service.last.body()).contains("checked the build");
    }

    @Test
    void failureOutcomeMappedToHighSeverity() {
        RecordingService service = new RecordingService();
        NotificationReportWriter writer = new NotificationReportWriter(service);
        AgentReport report = new AgentReport("nightwatch", "Nightwatch",
                AgentReport.Outcome.FAILURE, "", List.of("deploy to prod"), "boom");

        writer.write(AgentSpec.create("nightwatch", "Nightwatch"), report);

        assertThat(service.last.severity()).isEqualTo(Severity.HIGH);
        assertThat(service.last.body()).contains("等你决定");
    }

    @Test
    void notifyExceptionIsSwallowed() {
        RecordingService service = new RecordingService();
        service.throwOnNotify = true;
        NotificationReportWriter writer = new NotificationReportWriter(service);
        AgentReport report = new AgentReport("id", "N", AgentReport.Outcome.SUCCESS, "x", List.of(), "");

        // Must not throw — a push failure must never break the run.
        writer.write(AgentSpec.create("id", "N"), report);
    }
}
