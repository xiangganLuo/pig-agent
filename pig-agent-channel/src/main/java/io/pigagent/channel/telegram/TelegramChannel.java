package io.pigagent.channel.telegram;

import io.pigagent.channel.Channel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.function.Consumer;

public final class TelegramChannel implements Channel {
    private static final Logger log = LoggerFactory.getLogger(TelegramChannel.class);
    private final String token;
    private volatile boolean running = false;

    public TelegramChannel(String token) { this.token = token; }

    @Override public String channelId() { return "telegram"; }
    @Override public String displayName() { return "Telegram Bot"; }

    @Override
    public void start(Consumer<String> messageHandler) {
        this.running = true;
        log.info("Telegram channel started (stub)");
    }

    @Override
    public void sendMessage(String message) {
        log.info("Telegram would send: {}", message);
    }

    @Override public void stop() { this.running = false; }
    @Override public boolean isRunning() { return running; }
}
