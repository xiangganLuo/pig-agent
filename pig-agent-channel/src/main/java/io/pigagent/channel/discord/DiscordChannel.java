package io.pigagent.channel.discord;

import io.pigagent.channel.Channel;
import java.util.function.Consumer;

public final class DiscordChannel implements Channel {
    private final String token;
    private volatile boolean running = false;

    public DiscordChannel(String token) { this.token = token; }

    @Override public String channelId() { return "discord"; }
    @Override public String displayName() { return "Discord Bot"; }

    @Override
    public void start(Consumer<String> messageHandler) {
        this.running = true;
        System.err.println("[Discord] Channel started (stub)");
    }

    @Override
    public void sendMessage(String message) {
        System.err.println("[Discord] Would send: " + message);
    }

    @Override public void stop() { this.running = false; }
    @Override public boolean isRunning() { return running; }
}
