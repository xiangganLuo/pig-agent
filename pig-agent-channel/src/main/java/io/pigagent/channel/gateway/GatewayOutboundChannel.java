package io.pigagent.channel.gateway;

import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.harness.agent.gateway.channel.OutboundAddress;
import io.pigagent.channel.outreach.NotificationRenderer;
import io.pigagent.channel.outreach.OutboundChannel;
import io.pigagent.core.outreach.Notification;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;

/**
 * The proactive-outreach send-seam retargeted onto a <b>native AgentScope 2.0 gateway channel</b>
 * (av2 Gateway enhancement, part 4). It adapts pig's {@link OutboundChannel} — the thin
 * "push an unsolicited {@link Notification} to a recipient" transport seam — onto a native
 * {@link Channel#deliver(OutboundAddress, List)} call, so when the native gateway path is enabled a
 * daily briefing / reminder / alert can be pushed out through a native platform adapter
 * (DingTalk/Feishu/…) instead of pig's custom-robot webhook.
 *
 * <p>This is the "native lacks proactive push, pig fills it" story realized against the native kernel:
 * the routing/guardrail/trigger stack above ({@code ChannelNotificationService}/{@code OutreachGate}/
 * {@code ScheduledOutreach}) is unchanged — only the terminal transport seam moves. pig's custom
 * {@code StrategyHttpChannel.send} path stays the default; this is registered as an additional/alternate
 * outbound target only when the native path is enabled.
 *
 * <p>Contract-faithful: never throws (a transport failure returns {@code false}) and never logs the
 * recipient token.
 *
 * <p>It is also an <b>outbound-only</b> pig {@link io.pigagent.channel.Channel} so it slots into the
 * existing outreach registry + {@code ChannelNotificationService} lookup ({@code instanceof
 * OutboundChannel}) with no change to that routing: <em>inbound</em> for a native channel flows through
 * the native gateway (not pig's {@code ChannelAgentBridge}), so {@link #start}/{@link #stop} are no-ops
 * and {@link #sendMessage} (in-conversation reply) is delegated to the gateway; only
 * {@link #send(String, Notification)} (unsolicited push) is the active path.
 */
public final class GatewayOutboundChannel implements io.pigagent.channel.Channel, OutboundChannel {

    private static final Logger log = LoggerFactory.getLogger(GatewayOutboundChannel.class);

    private final io.agentscope.harness.agent.gateway.channel.Channel nativeChannel;

    public GatewayOutboundChannel(io.agentscope.harness.agent.gateway.channel.Channel nativeChannel) {
        this.nativeChannel = Objects.requireNonNull(nativeChannel, "nativeChannel");
    }

    /** The pig channel id this outbound target corresponds to (the native channel's id). */
    @Override
    public String channelId() {
        return nativeChannel.channelId();
    }

    @Override
    public String displayName() {
        return "Gateway outbound (" + nativeChannel.channelId() + ")";
    }

    /** No-op: inbound for a native channel is handled by the native adapter on the gateway. */
    @Override
    public void start(Consumer<String> messageHandler) {
        // intentionally empty — this is an outbound-only adapter
    }

    /**
     * In-conversation reply. Native gateway channels deliver their own replies, so this is a best-effort
     * push to the channel's fixed destination (never throws). Proactive outreach uses
     * {@link #send(String, Notification)} instead.
     */
    @Override
    public void sendMessage(String message) {
        try {
            Msg msg = Msg.builder().name("assistant").role(MsgRole.ASSISTANT)
                    .content(TextBlock.builder().text(message).build()).build();
            nativeChannel.deliver(OutboundAddress.direct(nativeChannel.channelId(), ""), List.of(msg));
        } catch (Exception e) {
            log.debug("Gateway outbound sendMessage failed (ignored): {}", e.getClass().getSimpleName());
        }
    }

    @Override
    public void stop() {
        // intentionally empty — the native adapter's lifecycle is owned by GatewayChannelKernel
    }

    @Override
    public boolean isRunning() {
        return true;
    }

    @Override
    public boolean send(String recipient, Notification notification) {
        try {
            String text = NotificationRenderer.render(notification);
            Msg msg = Msg.builder()
                    .name("assistant")
                    .role(MsgRole.ASSISTANT)
                    .content(TextBlock.builder().text(text).build())
                    .build();
            OutboundAddress address = OutboundAddress.direct(nativeChannel.channelId(),
                    recipient == null ? "" : recipient);
            nativeChannel.deliver(address, List.of(msg));
            return true;
        } catch (Exception e) {
            // Best-effort push; do not echo the recipient or the notification body.
            log.warn("Gateway outbound send failed on channel '{}': {}",
                    nativeChannel.channelId(), e.getClass().getSimpleName());
            return false;
        }
    }
}
