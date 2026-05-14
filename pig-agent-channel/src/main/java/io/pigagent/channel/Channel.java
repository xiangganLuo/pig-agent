package io.pigagent.channel;

import java.util.function.Consumer;

public interface Channel {
    String channelId();
    String displayName();
    void start(Consumer<String> messageHandler);
    void sendMessage(String message);
    void stop();
    boolean isRunning();
}
