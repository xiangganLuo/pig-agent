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
 *
 * <p><b>Honesty flag ({@code functional}).</b> Not every constant is a working transport. DingTalk,
 * Feishu, Webhook and Stdin round-trip for real (HTTP in + reply out). Telegram and Discord are pure
 * <em>stubs</em> ({@code start}/{@code sendMessage} only log), and Slack has a real inbound but a
 * stubbed outbound (it cannot post a reply) — none can hold a conversation, so they are flagged
 * {@code functional=false}. Callers (the seed config, {@code /channel}) use {@link #isFunctional()}
 * to mark them clearly and refuse to enable/test them, rather than pretending they connect.
 */
public enum ChannelType {

    TELEGRAM("telegram", "Telegram Bot", false, cfg -> new TelegramChannel(cfg.getToken())),
    DISCORD("discord", "Discord Bot", false, cfg -> new DiscordChannel(cfg.getToken())),
    WEBHOOK("webhook", "HTTP Webhook", true, cfg -> new WebhookChannel(cfg.getPort(), cfg.getPath(), cfg.getToken())),
    SLACK("slack", "Slack Events", false, cfg -> new SlackChannel(cfg.getPort(), cfg.getPath(), cfg.getSigningSecret())),
    STDIN("stdin", "CLI Stdin Pipe", true, cfg -> new StdinPipeChannel()),
    DINGTALK("dingtalk", "DingTalk Robot", true, cfg -> new StrategyHttpChannel(
            new DingTalkStrategy(cfg.getWebhookUrl(), cfg.getSignSecret(), WebhookSender.jdk()),
            cfg.getPort(), cfg.getPath())),
    FEISHU("feishu", "Feishu/Lark Robot", true, cfg -> new StrategyHttpChannel(
            new FeishuStrategy(cfg.getWebhookUrl(), cfg.getSignSecret(), cfg.getVerificationToken(),
                    WebhookSender.jdk()),
            cfg.getPort(), cfg.getPath()));

    private final String id;
    private final String displayName;
    private final boolean functional;
    private final Function<ChannelConfig, Channel> builder;

    ChannelType(String id, String displayName, boolean functional, Function<ChannelConfig, Channel> builder) {
        this.id = id;
        this.displayName = displayName;
        this.functional = functional;
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

    /**
     * Whether this channel is a working transport (can round-trip a conversation). {@code false} for
     * the stubs (Telegram/Discord) and the reply-less Slack outbound — those are placeholders, not
     * usable channels, and callers should mark/refuse them rather than pretend they connect.
     */
    public boolean isFunctional() {
        return functional;
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
