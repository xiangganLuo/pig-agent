package io.pigagent.channel.outreach;

import io.pigagent.core.outreach.Notification;
import io.pigagent.core.outreach.NotificationResult;
import io.pigagent.core.outreach.NotificationType;
import io.pigagent.core.outreach.OutreachGate;
import io.pigagent.core.outreach.OutreachPolicy;
import io.pigagent.core.outreach.Severity;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/** Routing, guardrail mapping and failure handling for {@link ChannelNotificationService}. */
class ChannelNotificationServiceTest {

    /** A fake outbound channel that records the last send and returns a scripted result. */
    private static final class FakeOutbound implements OutboundChannel {
        String recipient;
        Notification notification;
        int calls;
        boolean result = true;
        boolean throwOnSend;

        @Override
        public boolean send(String recipient, Notification notification) {
            this.recipient = recipient;
            this.notification = notification;
            this.calls++;
            if (throwOnSend) {
                throw new RuntimeException("network down");
            }
            return result;
        }
    }

    private static OutreachPolicy allowAll() {
        return new OutreachPolicy(true, OutreachPolicy.QuietHours.disabled(), 0, Duration.ofHours(1), Duration.ZERO);
    }

    private static Notification msg(String title, String body, Severity sev) {
        return Notification.of(NotificationType.MESSAGE, sev, title, body);
    }

    @Test
    void routesToCorrectOutboundChannelAndDelivers() {
        FakeOutbound feishu = new FakeOutbound();
        ChannelNotificationService svc = new ChannelNotificationService(
                id -> "feishu".equals(id) ? Optional.of(feishu) : Optional.empty(),
                new OutreachGate(allowAll()), () -> "feishu", () -> "boss");

        NotificationResult r = svc.notify(msg("t", "b", Severity.NORMAL).withTarget("feishu", "boss"));

        assertThat(r.delivered()).isTrue();
        assertThat(feishu.calls).isEqualTo(1);
        assertThat(feishu.recipient).isEqualTo("boss");
    }

    @Test
    void noOutboundChannelYieldsHelpfulErrorWithoutLeakingRecipient() {
        ChannelNotificationService svc = new ChannelNotificationService(
                id -> Optional.empty(), new OutreachGate(allowAll()), () -> "telegram", () -> "secret-token");

        NotificationResult r = svc.notify(msg("t", "b", Severity.NORMAL));

        assertThat(r.outcome()).isEqualTo(NotificationResult.Outcome.NO_CHANNEL);
        assertThat(r.detail()).contains("telegram");
        assertThat(r.detail()).doesNotContain("secret-token");
    }

    @Test
    void sendFailureReturnsFailed() {
        FakeOutbound ob = new FakeOutbound();
        ob.result = false;
        ChannelNotificationService svc = new ChannelNotificationService(
                id -> Optional.of(ob), new OutreachGate(allowAll()), () -> "feishu", () -> "boss");

        NotificationResult r = svc.notify(msg("t", "b", Severity.NORMAL));

        assertThat(r.outcome()).isEqualTo(NotificationResult.Outcome.FAILED);
    }

    @Test
    void sendThrowIsCaughtAndReturnsFailed() {
        FakeOutbound ob = new FakeOutbound();
        ob.throwOnSend = true;
        ChannelNotificationService svc = new ChannelNotificationService(
                id -> Optional.of(ob), new OutreachGate(allowAll()), () -> "feishu", () -> "boss");

        NotificationResult r = svc.notify(msg("t", "b", Severity.NORMAL));

        assertThat(r.outcome()).isEqualTo(NotificationResult.Outcome.FAILED);
    }

    @Test
    void disabledIsNoOp() {
        FakeOutbound ob = new FakeOutbound();
        ChannelNotificationService svc = new ChannelNotificationService(
                id -> Optional.of(ob), new OutreachGate(OutreachPolicy.disabled()), () -> "feishu", () -> "boss");

        NotificationResult r = svc.notify(msg("t", "b", Severity.NORMAL));

        assertThat(r.outcome()).isEqualTo(NotificationResult.Outcome.DISABLED);
        assertThat(ob.calls).isZero(); // never looked up or sent
    }

    @Test
    void resolvesDefaultChannelAndRecipientWhenTargetBlank() {
        FakeOutbound feishu = new FakeOutbound();
        ChannelNotificationService svc = new ChannelNotificationService(
                id -> "feishu".equals(id) ? Optional.of(feishu) : Optional.empty(),
                new OutreachGate(allowAll()), () -> "feishu", () -> "default-user");

        NotificationResult r = svc.notify(msg("t", "b", Severity.NORMAL)); // no explicit target

        assertThat(r.delivered()).isTrue();
        assertThat(feishu.recipient).isEqualTo("default-user");
    }

    @Test
    void noTargetAndNoDefaultYieldsNoChannel() {
        ChannelNotificationService svc = new ChannelNotificationService(
                id -> Optional.of(new FakeOutbound()), new OutreachGate(allowAll()), () -> null, () -> null);

        NotificationResult r = svc.notify(msg("t", "b", Severity.NORMAL));

        assertThat(r.outcome()).isEqualTo(NotificationResult.Outcome.NO_CHANNEL);
    }

    @Test
    void rateLimitDecisionMapsAndSkipsSend() {
        FakeOutbound ob = new FakeOutbound();
        OutreachPolicy p = new OutreachPolicy(true, OutreachPolicy.QuietHours.disabled(),
                1, Duration.ofHours(1), Duration.ZERO);
        ChannelNotificationService svc = new ChannelNotificationService(
                id -> Optional.of(ob), new OutreachGate(p), () -> "feishu", () -> "boss");

        NotificationResult first = svc.notify(msg("t1", "1", Severity.NORMAL));
        NotificationResult second = svc.notify(msg("t2", "2", Severity.NORMAL));

        assertThat(first.delivered()).isTrue();
        assertThat(second.outcome()).isEqualTo(NotificationResult.Outcome.RATE_LIMITED);
        assertThat(ob.calls).isEqualTo(1); // second suppressed before send
    }
}
