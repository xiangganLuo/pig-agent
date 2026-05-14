package io.pigagent.channel;

public record ChannelMessageReceivedEvent(String channelId, String userId, String content) {}
