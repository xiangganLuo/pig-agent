package io.pigagent.channel;

import io.pigagent.config.PigAgentConfig.ChannelConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Builds channel adapters from {@code channels.<id>} configuration and centralizes the enable gate.
 *
 * <p>A disabled or absent config produces no channel; an enabled but unknown id is logged and
 * skipped; an enabled known id yields the corresponding adapter. The known kinds and how to construct
 * them are the single-source-of-truth {@link ChannelType} <b>enum registry</b> — this factory is
 * driven by it (no ad-hoc string {@code switch}), so adding a channel means adding a
 * {@link ChannelType} constant, not editing this class. Channels default to disabled, so an
 * unconfigured deployment starts no channels (backward compatible).
 */
public final class ChannelFactory {

    private static final Logger log = LoggerFactory.getLogger(ChannelFactory.class);

    /** Build the channel for {@code id} if it is enabled and known; empty otherwise. */
    public Optional<Channel> create(String id, ChannelConfig cfg) {
        if (id == null || cfg == null || !cfg.isEnabled()) {
            return Optional.empty();
        }
        Optional<ChannelType> type = ChannelType.fromId(id);
        if (type.isEmpty()) {
            log.warn("Channel '{}' is enabled but no adapter exists for it — skipping", id);
            return Optional.empty();
        }
        return Optional.of(type.get().create(cfg));
    }

    /** Build all enabled, known channels from the {@code channels} config map. */
    public List<Channel> createEnabled(Map<String, ChannelConfig> configs) {
        List<Channel> channels = new ArrayList<>();
        if (configs == null) {
            return channels;
        }
        for (Map.Entry<String, ChannelConfig> entry : configs.entrySet()) {
            create(entry.getKey(), entry.getValue()).ifPresent(channels::add);
        }
        return channels;
    }
}
