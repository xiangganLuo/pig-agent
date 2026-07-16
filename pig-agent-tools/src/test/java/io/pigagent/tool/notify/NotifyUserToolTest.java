package io.pigagent.tool.notify;

import io.pigagent.core.outreach.Notification;
import io.pigagent.core.outreach.NotificationResult;
import io.pigagent.core.outreach.NotificationService;
import io.pigagent.core.outreach.Severity;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** Return-contract, urgency parsing and availability gating for {@link NotifyUserTool}. */
class NotifyUserToolTest {

    /** Records the last notification and returns a scripted result. */
    private static final class FakeService implements NotificationService {
        Notification last;
        NotificationResult scripted = NotificationResult.delivered("ok");

        @Override
        public NotificationResult notify(Notification notification) {
            this.last = notification;
            return scripted;
        }
    }

    @Test
    void deliveredReturnsSuccessLine() {
        FakeService svc = new FakeService();
        NotifyUserTool tool = new NotifyUserTool(svc, () -> true);

        String out = tool.notifyUser("Hi", "build is green", "false");

        assertThat(out).isEqualTo("Notification sent to the user.");
        assertThat(svc.last.severity()).isEqualTo(Severity.NORMAL);
    }

    @Test
    void urgentFlagRaisesSeverity() {
        FakeService svc = new FakeService();
        NotifyUserTool tool = new NotifyUserTool(svc, () -> true);

        tool.notifyUser("Prod down", "db unreachable", "true");

        assertThat(svc.last.severity()).isEqualTo(Severity.URGENT);
        assertThat(svc.last.urgent()).isTrue();
    }

    @Test
    void suppressionReturnsPlainStatusNotError() {
        FakeService svc = new FakeService();
        svc.scripted = NotificationResult.of(NotificationResult.Outcome.QUIET_HOURS, "quiet_hours");
        NotifyUserTool tool = new NotifyUserTool(svc, () -> true);

        String out = tool.notifyUser("Hi", "later", "false");

        assertThat(out).doesNotStartWith("{\"error\"");
        assertThat(out).contains("guardrail").contains("quiet_hours");
    }

    @Test
    void noChannelReturnsCanonicalError() {
        FakeService svc = new FakeService();
        svc.scripted = NotificationResult.of(NotificationResult.Outcome.NO_CHANNEL,
                "no outbound channel 'telegram' available");
        NotifyUserTool tool = new NotifyUserTool(svc, () -> true);

        String out = tool.notifyUser("Hi", "x", "false");

        assertThat(out).startsWith("{\"error\"");
        assertThat(out).contains("telegram");
    }

    @Test
    void failedReturnsCanonicalError() {
        FakeService svc = new FakeService();
        svc.scripted = NotificationResult.of(NotificationResult.Outcome.FAILED, "channel 'feishu' reported failure");
        NotifyUserTool tool = new NotifyUserTool(svc, () -> true);

        String out = tool.notifyUser("Hi", "x", "false");

        assertThat(out).startsWith("{\"error\"");
    }

    @Test
    void availabilityTracksEnabledFlag() {
        NotifyUserTool enabled = new NotifyUserTool(new FakeService(), () -> true);
        NotifyUserTool disabled = new NotifyUserTool(new FakeService(), () -> false);

        assertThat(enabled.availabilityToolNames()).containsExactly("notifyUser");
        assertThat(enabled.checkAvailability().available()).isTrue();
        assertThat(disabled.checkAvailability().available()).isFalse();
        assertThat(disabled.checkAvailability().reason()).contains("outreach disabled");
    }

    @Test
    void nullUrgentTreatedAsNonUrgent() {
        FakeService svc = new FakeService();
        NotifyUserTool tool = new NotifyUserTool(svc, () -> true);

        tool.notifyUser("Hi", "x", null);

        assertThat(svc.last.severity()).isEqualTo(Severity.NORMAL);
    }
}
