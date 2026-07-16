package io.pigagent.channel.outreach;

import io.pigagent.core.outreach.Notification;

/**
 * An <b>optional</b> capability a {@link io.pigagent.channel.Channel} MAY implement to send an
 * <em>unsolicited</em> {@link Notification} to a recipient — proactive outreach, as opposed to
 * {@link io.pigagent.channel.Channel#sendMessage} which replies within an ongoing conversation.
 *
 * <p>Inbound-only channels simply do not implement this interface (they then don't participate in
 * outreach routing — a graceful, no-config degradation). Implementations MUST NOT throw (a transport
 * failure returns {@code false}) and MUST NOT log the recipient token.
 *
 * <p>This is deliberately a <b>thin transport seam</b>: it does not manage sessions or route between
 * agents. That keeps the cost of replacing it (e.g. with a native channel gateway's push adapter)
 * minimal — the routing/guardrail/trigger logic above it stays unchanged.
 */
public interface OutboundChannel {

    /**
     * Deliver {@code notification} to {@code recipient} (a channel-specific address; may be blank when
     * the channel's transport has a single fixed destination, e.g. a robot webhook).
     *
     * @return {@code true} if handed to the transport successfully, {@code false} on failure
     */
    boolean send(String recipient, Notification notification);
}
