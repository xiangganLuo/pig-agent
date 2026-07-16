package io.pigagent.channel.gateway;

import io.agentscope.core.message.ContentBlock;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.TextBlock;
import io.agentscope.harness.agent.gateway.Gateway;
import io.agentscope.harness.agent.gateway.channel.Channel;
import io.agentscope.harness.agent.gateway.channel.ChannelConfig;
import io.agentscope.harness.agent.gateway.channel.InboundMessage;
import io.agentscope.harness.agent.gateway.channel.OutboundAddress;
import io.pigagent.core.outreach.Notification;
import io.pigagent.core.outreach.NotificationType;
import io.pigagent.core.outreach.Severity;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * av2 Gateway enhancement (part 4) — {@link GatewayOutboundChannel} retargets pig's proactive-outreach
 * send-seam onto a native gateway channel's {@link Channel#deliver}. Proves the rendered notification is
 * pushed through the native channel, and a transport failure is swallowed (returns {@code false}, never
 * throws, never echoes the recipient).
 */
class GatewayOutboundChannelTest {

    private static String text(List<Msg> msgs) {
        StringBuilder sb = new StringBuilder();
        for (Msg m : msgs) {
            for (ContentBlock b : m.getContent()) {
                if (b instanceof TextBlock t) {
                    sb.append(t.getText());
                }
            }
        }
        return sb.toString();
    }

    /** Records deliveries; optionally throws to simulate a transport failure. */
    static final class RecordingChannel implements Channel {
        final List<Msg> delivered = new ArrayList<>();
        OutboundAddress lastAddress;
        final boolean fail;
        RecordingChannel(boolean fail) { this.fail = fail; }
        @Override public String channelId() { return "dingtalk"; }
        @Override public ChannelConfig config() { return ChannelConfig.of("dingtalk", "default"); }
        @Override public void init(Gateway gateway) { }
        @Override public Mono<Msg> dispatch(InboundMessage message) { return Mono.empty(); }
        @Override public void deliver(OutboundAddress address, List<Msg> messages) {
            if (fail) {
                throw new RuntimeException("transport down");
            }
            this.lastAddress = address;
            this.delivered.addAll(messages);
        }
    }

    private static Notification notification() {
        return Notification.of(NotificationType.BRIEFING, Severity.NORMAL,
                "Daily briefing", "Two tasks are due today.");
    }

    @Test
    void deliversRenderedNotificationThroughNativeChannel() {
        RecordingChannel native0 = new RecordingChannel(false);
        GatewayOutboundChannel outbound = new GatewayOutboundChannel(native0);

        boolean ok = outbound.send("user-123", notification());

        assertThat(ok).isTrue();
        assertThat(text(native0.delivered)).contains("Daily briefing").contains("Two tasks are due today.");
        assertThat(native0.lastAddress.channelId()).isEqualTo("dingtalk");
        assertThat(native0.lastAddress.to()).isEqualTo("user-123");
        assertThat(outbound.channelId()).isEqualTo("dingtalk");
    }

    @Test
    void transportFailureReturnsFalseWithoutThrowing() {
        GatewayOutboundChannel outbound = new GatewayOutboundChannel(new RecordingChannel(true));

        boolean[] result = {true};
        assertThatCode(() -> result[0] = outbound.send("r", notification())).doesNotThrowAnyException();
        assertThat(result[0]).isFalse();
    }

    @Test
    void blankRecipientIsToleratedAsFixedDestination() {
        RecordingChannel native0 = new RecordingChannel(false);
        GatewayOutboundChannel outbound = new GatewayOutboundChannel(native0);

        assertThat(outbound.send(null, notification())).isTrue();
        assertThat(native0.lastAddress.to()).isEmpty();
    }
}
