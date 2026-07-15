package io.pigagent.channel.strategy;

import io.pigagent.channel.Channel;
import io.pigagent.channel.http.HttpChannelServer;
import io.pigagent.channel.http.InboundHttp;
import io.pigagent.channel.http.OutboundHttp;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.function.Consumer;

/**
 * A generic HTTP-robot {@link Channel} whose per-platform behaviour is entirely delegated to an
 * injected {@link ChannelStrategy}. It owns only the shared transport glue: the inbound
 * {@link HttpChannelServer} (whose {@code process} it supplies) and — via the strategy — the outbound
 * webhook. This is the reuse point requested by the Strategy design: DingTalk and Feishu are just
 * strategies over this one channel; adding another HTTP robot needs no new transport code.
 *
 * <p>Inbound flow ({@link #process}): {@code strategy.inbound(req)} → a direct response is written
 * back as-is; a routable message is handed to the bound handler (the {@code ChannelAgentBridge}, which
 * runs the agent synchronously and calls {@link #sendMessage} with the reply); the HTTP response is
 * then the strategy's fixed {@link ChannelStrategy#ack()}. Outbound ({@link #sendMessage}) delegates
 * to {@link ChannelStrategy#send} — the reply travels the platform's webhook, not the ack body. The
 * request handler {@code process} is pure enough to unit-test without a socket (a fake strategy).
 *
 * <p><b>Skeleton note:</b> the agent runs synchronously inside {@code process} (the bridge blocks on
 * the turn), so the ack is delayed until the reply has been sent; a production adapter should ack fast
 * (200) and run the agent + webhook reply on a background thread to respect platform event timeouts.
 */
public final class StrategyHttpChannel implements Channel {

    private static final Logger log = LoggerFactory.getLogger(StrategyHttpChannel.class);

    private final ChannelStrategy strategy;
    private final int configuredPort;
    private final String configuredPath;

    private final HttpChannelServer server = new HttpChannelServer();
    private volatile boolean running;
    private Consumer<String> handler;

    public StrategyHttpChannel(ChannelStrategy strategy, int port, String path) {
        this.strategy = strategy;
        this.configuredPort = port;
        this.configuredPath = (path == null || path.isBlank()) ? null : path;
    }

    @Override
    public String channelId() {
        return strategy.channelId();
    }

    @Override
    public String displayName() {
        return strategy.displayName();
    }

    @Override
    public void start(Consumer<String> messageHandler) {
        bind(messageHandler);
        int port = effectivePort();
        String path = effectivePath();
        try {
            server.start(port, path, this::process);
            running = true;
            log.info("{} channel listening on port {} path {}", strategy.channelId(), port, path);
        } catch (IOException e) {
            log.error("Failed to start {} channel on port {}: {}", strategy.channelId(), port, e.getMessage());
        }
    }

    /** Wire the inbound handler without starting the transport (used by {@link #start} and tests). */
    void bind(Consumer<String> messageHandler) {
        this.handler = messageHandler;
    }

    int effectivePort() {
        return configuredPort > 0 ? configuredPort : strategy.defaultPort();
    }

    String effectivePath() {
        return configuredPath != null ? configuredPath : strategy.defaultPath();
    }

    /** The actual bound port after {@link #start}, or -1 if not started. */
    public int boundPort() {
        return server.boundPort();
    }

    /** Pure request handler: delegate to the strategy for verify/parse, route a message, then ack. */
    OutboundHttp process(InboundHttp request) {
        Inbound inbound = strategy.inbound(request);
        if (inbound.isDirect()) {
            return inbound.response();
        }
        if (inbound.hasMessage() && handler != null) {
            handler.accept(inbound.message());
        }
        return strategy.ack();
    }

    @Override
    public void sendMessage(String message) {
        // Outbound travels the platform's webhook (out-of-band), not the inbound ack body.
        strategy.send(message);
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
