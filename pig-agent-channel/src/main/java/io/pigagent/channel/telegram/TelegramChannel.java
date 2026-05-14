package io.pigagent.channel.telegram;

import io.pigagent.channel.Channel;
import java.util.function.Consumer;

public final class TelegramChannel implements Channel {
    private final String token;
    private volatile boolean running = false;

    public TelegramChannel(String token) { this.token = token; }

    @Override public String channelId() { return "telegram"; }
    @Override public String displayName() { return "Telegram Bot"; }

    @Override
    public void start(Consumer<String> messageHandler) {
        this.running = true;
        System.err.println("[Telegram] Channel started (stub)");
    }

    @Override
    public void sendMessage(String message) {
        System.err.println("[Telegram] Would send: " + message);
    }

    @Override public void stop() { this.running = false; }
    @Override public boolean isRunning() { return running; }
}
