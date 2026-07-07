package io.pigagent.channel.discord;

import io.pigagent.channel.Channel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.function.Consumer;

public final class DiscordChannel implements Channel {
    private static final Logger log = LoggerFactory.getLogger(DiscordChannel.class);
    private final String token;
    private volatile boolean running = false;

    public DiscordChannel(String token) { this.token = token; }

    @Override public String channelId() { return "discord"; }
    @Override public String displayName() { return "Discord Bot"; }

    @Override
    public void start(Consumer<String> messageHandler) {
        this.running = true;
        log.info("Discord channel started (stub)");
    }

    @Override
    public void sendMessage(String message) {
        log.info("Discord would send: {}", message);
    }

    @Override public void stop() { this.running = false; }
    @Override public boolean isRunning() { return running; }
}
