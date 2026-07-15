package io.pigagent.channel.feishu;

import io.pigagent.channel.http.InboundHttp;
import io.pigagent.channel.http.OutboundHttp;
import io.pigagent.channel.http.WebhookSender;
import io.pigagent.channel.strategy.ChannelStrategy;
import io.pigagent.channel.strategy.Inbound;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Optional;

/**
 * {@link ChannelStrategy} for the Feishu/Lark (飞书) robot + event subscription, over
 * {@code StrategyHttpChannel}.
 *
 * <p><b>Inbound</b> event subscription: a {@code url_verification} POST is answered by echoing its
 * {@code challenge}; an {@code im.message.receive_v1} event has its text extracted and routed. When a
 * {@code verification-token} is configured, the {@code X-Lark-Signature} is verified first (invalid →
 * 401). <b>Outbound</b>: {@link #send} POSTs the text payload to the configured custom-bot
 * {@code webhook-url} through the injected {@link WebhookSender}, embedding {@code timestamp}/{@code
 * sign} in the body when a secret is set. Credentials are only compared/used to sign — never logged.
 */
public final class FeishuStrategy implements ChannelStrategy {

    private static final Logger log = LoggerFactory.getLogger(FeishuStrategy.class);

    static final int DEFAULT_PORT = 8689;
    static final String DEFAULT_PATH = "/feishu";
    private static final String SIG_HEADER = "x-lark-signature";
    private static final String TS_HEADER = "x-lark-request-timestamp";
    private static final String NONCE_HEADER = "x-lark-request-nonce";

    private final String webhookUrl;         // outbound custom-bot webhook (nullable → no outbound)
    private final String signSecret;         // outbound signing secret (nullable → unsigned outbound)
    private final String verificationToken;  // inbound event signature token (nullable → no verify)
    private final WebhookSender sender;

    public FeishuStrategy(String webhookUrl, String signSecret, String verificationToken, WebhookSender sender) {
        this.webhookUrl = blankToNull(webhookUrl);
        this.signSecret = blankToNull(signSecret);
        this.verificationToken = blankToNull(verificationToken);
        this.sender = sender;
    }

    @Override
    public String channelId() {
        return "feishu";
    }

    @Override
    public String displayName() {
        return "Feishu/Lark Robot";
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
        if (verificationToken != null && !verify(request)) {
            return Inbound.respond(OutboundHttp.json(401, "{\"error\":\"unauthorized\"}"));
        }
        Optional<String> challenge = FeishuCodec.challenge(request.body());
        if (challenge.isPresent()) {
            return Inbound.respond(OutboundHttp.json(200, FeishuCodec.buildChallengeResponse(challenge.get())));
        }
        return FeishuCodec.extractMessage(request.body()).map(Inbound::route).orElseGet(Inbound::ignore);
    }

    private boolean verify(InboundHttp request) {
        return FeishuCodec.verifySignature(verificationToken, request.header(TS_HEADER),
                request.header(NONCE_HEADER), FeishuCodec.encryptField(request.body()), request.header(SIG_HEADER));
    }

    @Override
    public OutboundHttp ack() {
        return OutboundHttp.json(200, "{}");
    }

    @Override
    public void send(String agentReply) {
        if (webhookUrl == null) {
            log.debug("Feishu outbound skipped: no webhook-url configured ({} chars)",
                    agentReply == null ? 0 : agentReply.length());
            return;
        }
        String payload;
        if (signSecret != null) {
            String ts = String.valueOf(System.currentTimeMillis() / 1000L);
            payload = FeishuCodec.buildSignedTextPayload(agentReply, ts, FeishuCodec.sign(ts, signSecret));
        } else {
            payload = FeishuCodec.buildTextPayload(agentReply);
        }
        sender.post(webhookUrl, payload);
    }

    private static String blankToNull(String s) {
        return (s == null || s.isBlank()) ? null : s;
    }
}
