package io.pigagent.channel.dingtalk;

import io.pigagent.channel.http.InboundHttp;
import io.pigagent.channel.http.OutboundHttp;
import io.pigagent.channel.http.WebhookSender;
import io.pigagent.channel.strategy.ChannelStrategy;
import io.pigagent.channel.strategy.Inbound;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * {@link ChannelStrategy} for the DingTalk (钉钉) custom robot, over {@code StrategyHttpChannel}.
 *
 * <p><b>Inbound</b> (outgoing robot): a {@code POST} whose body carries {@code text.content}. When a
 * {@code sign-secret} is configured, the {@code timestamp}/{@code sign} headers are verified via
 * {@link DingTalkCodec#verify} (invalid → 401). <b>Outbound</b>: {@link #send} POSTs the text payload
 * to the configured robot {@code webhook-url} through the injected {@link WebhookSender}, appending a
 * signed {@code &timestamp=..&sign=..} when a secret is set. Credentials are only compared/used to
 * sign — never logged.
 */
public final class DingTalkStrategy implements ChannelStrategy {

    private static final Logger log = LoggerFactory.getLogger(DingTalkStrategy.class);

    static final int DEFAULT_PORT = 8688;
    static final String DEFAULT_PATH = "/dingtalk";
    private static final String TS_HEADER = "timestamp";
    private static final String SIGN_HEADER = "sign";

    private final String webhookUrl;  // outbound robot webhook (nullable → no outbound)
    private final String signSecret;  // outbound + inbound signing secret (nullable → no verify/sign)
    private final WebhookSender sender;

    public DingTalkStrategy(String webhookUrl, String signSecret, WebhookSender sender) {
        this.webhookUrl = blankToNull(webhookUrl);
        this.signSecret = blankToNull(signSecret);
        this.sender = sender;
    }

    @Override
    public String channelId() {
        return "dingtalk";
    }

    @Override
    public String displayName() {
        return "DingTalk Robot";
    }

    @Override
    public int defaultPort() {
        return DEFAULT_PORT;
    }

    @Override
    public String defaultPath() {
        return DEFAULT_PATH;
    }

    @Override
    public Inbound inbound(InboundHttp request) {
        if (!"POST".equalsIgnoreCase(request.method())) {
            return Inbound.respond(OutboundHttp.json(405, "{\"error\":\"method not allowed\"}"));
        }
        if (signSecret != null
                && !DingTalkCodec.verify(request.header(TS_HEADER), request.header(SIGN_HEADER), signSecret)) {
            return Inbound.respond(OutboundHttp.json(401, "{\"error\":\"unauthorized\"}"));
        }
        String message = DingTalkCodec.extractMessage(request.body());
        return message.isBlank() ? Inbound.ignore() : Inbound.route(message);
    }

    @Override
    public OutboundHttp ack() {
        return OutboundHttp.json(200, "{}");
    }

    @Override
    public void send(String agentReply) {
        if (webhookUrl == null) {
            log.debug("DingTalk outbound skipped: no webhook-url configured ({} chars)",
                    agentReply == null ? 0 : agentReply.length());
            return;
        }
        String payload = DingTalkCodec.buildTextPayload(agentReply);
        String url = webhookUrl;
        if (signSecret != null) {
            url = DingTalkCodec.signedUrl(webhookUrl, signSecret, String.valueOf(System.currentTimeMillis()));
        }
        sender.post(url, payload);
    }

    private static String blankToNull(String s) {
        return (s == null || s.isBlank()) ? null : s;
    }
}
