package io.pigagent.channel.webhook;

import io.pigagent.channel.Channel;
import io.pigagent.channel.http.HttpChannelServer;
import io.pigagent.channel.http.InboundHttp;
import io.pigagent.channel.http.OutboundHttp;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.function.Consumer;

/**
 * Generic HTTP webhook channel: an inbound {@code POST} carries a message (JSON {@code message}/
 * {@code text} field or a plain-text body), which is routed synchronously through the
 * {@code ChannelAgentBridge} to the agent; the agent's reply is written back in the same HTTP
 * response body ({@code {"reply": "..."}}). Non-POST methods yield 405, an empty message 400, and —
 * when a {@code token} is configured — a missing/wrong {@code X-Auth-Token} (or
 * {@code Authorization: Bearer}) header yields 401.
 *
 * <p>The request-processing logic ({@link #process}) is pure and unit-tested without a socket; the
 * transport lives in {@link HttpChannelServer}. The reply is correlated with the in-flight request
 * via a per-request {@link ThreadLocal}: {@link #runAndCapture} sets a buffer, invokes the handler
 * (which blocks on the agent turn and calls {@link #sendMessage} with the full reply), then returns
 * the captured text. Credentials are never logged or echoed.
 */
public final class WebhookChannel implements Channel {

    private static final Logger log = LoggerFactory.getLogger(WebhookChannel.class);

    static final int DEFAULT_PORT = 8686;
    static final String DEFAULT_PATH = "/webhook";
    private static final String AUTH_HEADER = "x-auth-token";
    private static final String BEARER_PREFIX = "Bearer ";

    private final int configuredPort;
    private final String path;
    private final String authToken; // nullable; when set, inbound must present it

    private final HttpChannelServer server = new HttpChannelServer();
    private final ThreadLocal<StringBuilder> replyCapture = new ThreadLocal<>();
    private volatile boolean running;
    private Consumer<String> handler;

    public WebhookChannel(int port, String path, String authToken) {
        this.configuredPort = port;
        this.path = (path == null || path.isBlank()) ? DEFAULT_PATH : path;
        this.authToken = (authToken == null || authToken.isBlank()) ? null : authToken;
    }

    @Override
    public String channelId() {
        return "webhook";
    }

    @Override
    public String displayName() {
        return "HTTP Webhook";
    }

    @Override
    public void start(Consumer<String> messageHandler) {
        bind(messageHandler);
        int port = effectivePort();
        try {
            server.start(port, path, this::process);
            running = true;
            log.info("Webhook channel listening on port {} path {}", port, path);
        } catch (IOException e) {
            log.error("Failed to start webhook channel on port {}: {}", port, e.getMessage());
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

    /** Pure request handler: method/auth gating, message extraction, agent run, reply. */
    OutboundHttp process(InboundHttp request) {
        if (!"POST".equalsIgnoreCase(request.method())) {
            return OutboundHttp.json(405, "{\"error\":\"method not allowed\"}");
        }
        if (authToken != null && !isAuthorized(request)) {
            return OutboundHttp.json(401, "{\"error\":\"unauthorized\"}");
        }
        String message = WebhookCodec.extractMessage(request.body(), request.header("content-type"));
        if (message.isEmpty()) {
            return OutboundHttp.json(400, "{\"error\":\"empty message\"}");
        }
        String reply = runAndCapture(message);
        return OutboundHttp.json(200, WebhookCodec.formatReply(reply));
    }

    private boolean isAuthorized(InboundHttp request) {
        if (authToken.equals(request.header(AUTH_HEADER))) {
            return true;
        }
        String auth = request.header("authorization");
        if (auth != null && auth.regionMatches(true, 0, BEARER_PREFIX, 0, BEARER_PREFIX.length())) {
            return authToken.equals(auth.substring(BEARER_PREFIX.length()).strip());
        }
        return false;
    }

    /** Run the bound handler capturing the agent reply it emits via {@link #sendMessage}. */
    String runAndCapture(String message) {
        replyCapture.set(new StringBuilder());
        try {
            if (handler != null) {
                handler.accept(message);
            }
            return replyCapture.get().toString();
        } finally {
            replyCapture.remove();
        }
    }

    @Override
    public void sendMessage(String message) {
        StringBuilder buffer = replyCapture.get();
        if (buffer != null) {
            buffer.append(message == null ? "" : message);
        } else {
            log.debug("Webhook reply outside request scope discarded ({} chars)",
                    message == null ? 0 : message.length());
        }
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
