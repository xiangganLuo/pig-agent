package io.pigagent.channel.strategy;

import io.pigagent.channel.http.InboundHttp;
import io.pigagent.channel.http.OutboundHttp;

/**
 * The <b>Strategy</b> for an HTTP-based robot channel: it captures the three behaviours that vary
 * from platform to platform, while the transport glue (inbound {@code HttpChannelServer} + outbound
 * {@code WebhookSender}) is shared by the generic {@link StrategyHttpChannel}. The three varying
 * behaviours are:
 * <ol>
 *   <li><b>signature / verification + inbound parsing</b> — {@link #inbound(InboundHttp)} verifies the
 *       request (returning a {@code 401} {@link Inbound#respond} on failure) and parses it into either
 *       a direct response (challenge echo / ack) or a routable user message;</li>
 *   <li><b>the fixed ack</b> — {@link #ack()} is written back after a message is routed (or for an
 *       ignored inbound);</li>
 *   <li><b>outbound reply/send</b> — {@link #send(String)} delivers the agent reply out-of-band via
 *       the platform's signed custom-robot webhook.</li>
 * </ol>
 * Adding an HTTP robot channel therefore means implementing one {@code ChannelStrategy} (plus its pure
 * codec) — no new transport code. Implementations MUST NOT log or echo credentials
 * (sign secret / verification token): they are only ever compared or used to sign.
 */
public interface ChannelStrategy {

    /** Stable channel id (the {@code channels.<id>} key), e.g. {@code dingtalk}. */
    String channelId();

    /** Human-readable name for logs / status. */
    String displayName();

    /** Built-in listen port used when the config leaves {@code port <= 0}. */
    int defaultPort();

    /** Built-in HTTP path used when the config leaves {@code path} blank. */
    String defaultPath();

    /**
     * Verify + parse an inbound request. Returns a direct response (challenge / {@code 401} /
     * {@code 405}) or a routable user message; see {@link Inbound}.
     */
    Inbound inbound(InboundHttp request);

    /** The fixed immediate ack written after routing a message (or for an ignored inbound). */
    OutboundHttp ack();

    /**
     * Deliver an agent reply out-of-band via the platform's signed custom-robot webhook. A no-op
     * (debug log) when no outbound webhook is configured. MUST NOT throw.
     */
    void send(String agentReply);
}
