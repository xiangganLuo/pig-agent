package io.pigagent.channel.outreach;

import io.pigagent.channel.http.InboundHttp;
import io.pigagent.channel.http.OutboundHttp;
import io.pigagent.channel.strategy.ChannelStrategy;
import io.pigagent.channel.strategy.Inbound;
import io.pigagent.channel.strategy.StrategyHttpChannel;
import io.pigagent.core.outreach.Notification;
import io.pigagent.core.outreach.NotificationType;
import io.pigagent.core.outreach.Severity;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** {@link StrategyHttpChannel} as an {@link OutboundChannel}: renders + dispatches via the strategy. */
class StrategyHttpChannelOutboundTest {

    private static final class RecordingStrategy implements ChannelStrategy {
        final List<String> sent = new ArrayList<>();
        boolean throwOnSend;

        @Override public String channelId() { return "fake"; }
        @Override public String displayName() { return "Fake Robot"; }
        @Override public int defaultPort() { return 8600; }
        @Override public String defaultPath() { return "/fake"; }
        @Override public Inbound inbound(InboundHttp request) { return Inbound.ignore(); }
        @Override public OutboundHttp ack() { return OutboundHttp.json(200, "{}"); }

        @Override
        public boolean send(String agentReply) {
            if (throwOnSend) {
                throw new RuntimeException("boom");
            }
            sent.add(agentReply);
            return true;
        }
    }

    @Test
    void sendRendersNotificationAndDelegatesToStrategy() {
        RecordingStrategy strategy = new RecordingStrategy();
        StrategyHttpChannel channel = new StrategyHttpChannel(strategy, 0, null);
        Notification n = Notification.of(NotificationType.BRIEFING, Severity.NORMAL, "标题", "正文");

        boolean ok = channel.send("recipient-token", n);

        assertThat(ok).isTrue();
        assertThat(strategy.sent).containsExactly("标题\n\n正文");
    }

    @Test
    void sendReturnsFalseAndDoesNotThrowWhenStrategyFails() {
        RecordingStrategy strategy = new RecordingStrategy();
        strategy.throwOnSend = true;
        StrategyHttpChannel channel = new StrategyHttpChannel(strategy, 0, null);

        boolean ok = channel.send("r", Notification.of(NotificationType.ALERT, Severity.URGENT, "t", "b"));

        assertThat(ok).isFalse();
    }
}
