package io.pigagent.channel;

import io.pigagent.channel.cli.StdinPipeChannel;
import io.pigagent.channel.discord.DiscordChannel;
import io.pigagent.channel.slack.SlackChannel;
import io.pigagent.channel.telegram.TelegramChannel;
import io.pigagent.channel.webhook.WebhookChannel;
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
 * skipped; an enabled known id ({@code telegram}/{@code discord}/{@code webhook}/{@code slack}/
 * {@code stdin}) yields the corresponding adapter. Channels default to disabled, so an unconfigured
 * deployment starts no channels (backward compatible). Adding a new adapter means adding a case
 * here — the frontend goes through this factory rather than an inline switch.
 */
public final class ChannelFactory {

    private static final Logger log = LoggerFactory.getLogger(ChannelFactory.class);

    /** Build the channel for {@code id} if it is enabled and known; empty otherwise. */
    public Optional<Channel> create(String id, ChannelConfig cfg) {
        if (id == null || cfg == null || !cfg.isEnabled()) {
            return Optional.empty();
        }
        Channel channel = switch (id) {
            case "telegram" -> new TelegramChannel(cfg.getToken());
            case "discord" -> new DiscordChannel(cfg.getToken());
            case "webhook" -> new WebhookChannel(cfg.getPort(), cfg.getPath(), cfg.getToken());
            case "slack" -> new SlackChannel(cfg.getPort(), cfg.getPath(), cfg.getSigningSecret());
            case "stdin" -> new StdinPipeChannel();
            default -> null;
        };
        if (channel == null) {
            log.warn("Channel '{}' is enabled but no adapter exists for it — skipping", id);
            return Optional.empty();
        }
        return Optional.of(channel);
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
