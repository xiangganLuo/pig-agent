package io.pigagent.channel;

import java.util.*;

public final class ChannelRegistry {
    private final Map<String, Channel> channels = new LinkedHashMap<>();

    public void register(Channel channel) { channels.put(channel.channelId(), channel); }
    public Optional<Channel> findById(String id) { return Optional.ofNullable(channels.get(id)); }
    public Collection<Channel> getAll() { return Collections.unmodifiableCollection(channels.values()); }
}
