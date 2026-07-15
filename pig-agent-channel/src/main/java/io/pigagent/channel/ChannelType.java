package io.pigagent.channel;

import io.pigagent.channel.cli.StdinPipeChannel;
import io.pigagent.channel.dingtalk.DingTalkStrategy;
import io.pigagent.channel.discord.DiscordChannel;
import io.pigagent.channel.feishu.FeishuStrategy;
import io.pigagent.channel.http.WebhookSender;
import io.pigagent.channel.slack.SlackChannel;
import io.pigagent.channel.strategy.StrategyHttpChannel;
import io.pigagent.channel.telegram.TelegramChannel;
import io.pigagent.channel.webhook.WebhookChannel;
import io.pigagent.config.PigAgentConfig.ChannelConfig;

import java.util.Optional;
import java.util.function.Function;

/**
 * The single registry of channel kinds. Each constant carries its {@code id} (the
 * {@code channels.<id>} config key), its {@code displayName}, and a builder that constructs the
 * {@link Channel} from a {@link ChannelConfig} (HTTP-robot kinds link to their {@code ChannelStrategy}
 * over the shared {@code StrategyHttpChannel}). {@link ChannelFactory} is driven entirely by this
 * enum — there is no ad-hoc string {@code switch} anywhere. <b>Adding a channel = adding one constant
 * here</b> (plus, for an HTTP robot, its strategy + codec); no edit to {@code ChannelFactory} or
 * {@code PigAgentCli} is needed.
 */
public enum ChannelType {

    TELEGRAM("telegram", "Telegram Bot", cfg -> new TelegramChannel(cfg.getToken())),
    DISCORD("discord", "Discord Bot", cfg -> new DiscordChannel(cfg.getToken())),
    WEBHOOK("webhook", "HTTP Webhook", cfg -> new WebhookChannel(cfg.getPort(), cfg.getPath(), cfg.getToken())),
    SLACK("slack", "Slack Events", cfg -> new SlackChannel(cfg.getPort(), cfg.getPath(), cfg.getSigningSecret())),
    STDIN("stdin", "CLI Stdin Pipe", cfg -> new StdinPipeChannel()),
    DINGTALK("dingtalk", "DingTalk Robot", cfg -> new StrategyHttpChannel(
            new DingTalkStrategy(cfg.getWebhookUrl(), cfg.getSignSecret(), WebhookSender.jdk()),
            cfg.getPort(), cfg.getPath())),
    FEISHU("feishu", "Feishu/Lark Robot", cfg -> new StrategyHttpChannel(
            new FeishuStrategy(cfg.getWebhookUrl(), cfg.getSignSecret(), cfg.getVerificationToken(),
                    WebhookSender.jdk()),
            cfg.getPort(), cfg.getPath()));

    private final String id;
    private final String displayName;
    private final Function<ChannelConfig, Channel> builder;

    ChannelType(String id, String displayName, Function<ChannelConfig, Channel> builder) {
        this.id = id;
        this.displayName = displayName;
        this.builder = builder;
    }

    /** The {@code channels.<id>} config key / registry key for this kind. */
    public String id() {
        return id;
    }

    /** Human-readable name for logs / status. */
    public String displayName() {
        return displayName;
    }

    /** Construct the channel adapter for this kind from its per-channel config. */
    public Channel create(ChannelConfig cfg) {
        return builder.apply(cfg);
    }

    /** Look up a kind by its {@code channels.<id>} key; empty for an unknown id. */
    public static Optional<ChannelType> fromId(String id) {
        if (id == null) {
            return Optional.empty();
        }
        for (ChannelType type : values()) {
            if (type.id.equals(id)) {
                return Optional.of(type);
            }
        }
        return Optional.empty();
    }
}
