package io.pigagent.channel.slack;

import io.pigagent.channel.Channel;
import io.pigagent.channel.http.HttpChannelServer;
import io.pigagent.channel.http.InboundHttp;
import io.pigagent.channel.http.OutboundHttp;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.Optional;
import java.util.function.Consumer;

/**
 * Slack Events API channel — a thin, tested skeleton. Inbound is fully implemented: it verifies the
 * Slack {@code v0} request signature (when a {@code signing-secret} is configured), answers the
 * {@code url_verification} handshake, and routes a genuine {@code message} event's text through the
 * {@code ChannelAgentBridge} to the agent (bot echoes ignored). The request logic lives in
 * {@link #process} (pure, unit-tested) + {@link SlackEventCodec}; the transport is
 * {@link HttpChannelServer}.
 *
 * <p><b>Outbound is a documented stub.</b> Slack replies must be delivered via a separate API call
 * ({@code chat.postMessage} with a bot token, or a POST to the event's {@code response_url}), which
 * needs an HTTP client / Slack SDK not wired here. {@link #sendMessage} therefore only logs that a
 * reply would be posted; the event is acked with an empty 200. A production adapter should also ack
 * first and run the agent asynchronously (Slack times out events after 3s) and reject stale
 * request timestamps.
 */
public final class SlackChannel implements Channel {

    private static final Logger log = LoggerFactory.getLogger(SlackChannel.class);

    static final int DEFAULT_PORT = 8687;
    static final String DEFAULT_PATH = "/slack/events";
    private static final String SIG_HEADER = "x-slack-signature";
    private static final String TS_HEADER = "x-slack-request-timestamp";

    private final int configuredPort;
    private final String path;
    private final String signingSecret; // nullable; when set, requests must be signed

    private final HttpChannelServer server = new HttpChannelServer();
    private volatile boolean running;
    private Consumer<String> handler;

    public SlackChannel(int port, String path, String signingSecret) {
        this.configuredPort = port;
        this.path = (path == null || path.isBlank()) ? DEFAULT_PATH : path;
        this.signingSecret = (signingSecret == null || signingSecret.isBlank()) ? null : signingSecret;
    }

    @Override
    public String channelId() {
        return "slack";
    }

    @Override
    public String displayName() {
        return "Slack Events";
    }

    @Override
    public void start(Consumer<String> messageHandler) {
        bind(messageHandler);
        int port = effectivePort();
        try {
            server.start(port, path, this::process);
            running = true;
            log.info("Slack channel listening on port {} path {}", port, path);
        } catch (IOException e) {
            log.error("Failed to start slack channel on port {}: {}", port, e.getMessage());
        }
    }

    /** Wire the inbound handler without starting the transport (used by {@link #start} and tests). */
    void bind(Consumer<String> messageHandler) {
        this.handler = messageHandler;
    }

    int effectivePort() {
        return configuredPort > 0 ? configuredPort : DEFAULT_PORT;
    }

    /** The actual bound port after {@link #start}, or -1 if not started. */
    public int boundPort() {
        return server.boundPort();
    }

    /** Pure request handler: signature check, URL-verification handshake, message dispatch. */
    OutboundHttp process(InboundHttp request) {
        if (!"POST".equalsIgnoreCase(request.method())) {
            return OutboundHttp.json(405, "{\"error\":\"method not allowed\"}");
        }
        if (signingSecret != null && !SlackEventCodec.verifySignature(
                signingSecret, request.header(TS_HEADER), request.body(), request.header(SIG_HEADER))) {
            return OutboundHttp.json(401, "{\"error\":\"unauthorized\"}");
        }
        Optional<String> challenge = SlackEventCodec.challenge(request.body());
        if (challenge.isPresent()) {
            return OutboundHttp.text(200, challenge.get());
        }
        SlackEventCodec.extractText(request.body()).ifPresent(text -> {
            if (handler != null) {
                handler.accept(text);
            }
        });
        // Slack expects a fast empty 200 ack; the reply is delivered out-of-band (stub, see class doc).
        return new OutboundHttp(200, null, null);
    }

    @Override
    public void sendMessage(String message) {
        // Outbound is a skeleton stub — a real adapter would POST to response_url / chat.postMessage.
        log.info("Slack outbound (skeleton): would post reply of {} chars",
                message == null ? 0 : message.length());
    }

    @Override
    public void stop() {
        running = false;
        server.stop();
    }

    @Override
    public boolean isRunning() {
        return running;
    }
}
