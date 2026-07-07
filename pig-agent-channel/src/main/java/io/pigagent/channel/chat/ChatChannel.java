package io.pigagent.channel.chat;

import io.pigagent.channel.Channel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.function.Consumer;

public final class ChatChannel implements Channel {
    private static final Logger log = LoggerFactory.getLogger(ChatChannel.class);
    private volatile boolean running = false;
    private Consumer<String> handler;

    @Override public String channelId() { return "chat"; }
    @Override public String displayName() { return "Terminal Chat"; }

    @Override
    public void start(Consumer<String> messageHandler) {
        this.handler = messageHandler;
        this.running = true;
    }

    @Override
    public void sendMessage(String message) { log.info("{}", message); }

    @Override
    public void stop() { this.running = false; }

    @Override
    public boolean isRunning() { return running; }

    public void onUserInput(String input) {
        if (handler != null) handler.accept(input);
    }
}
