package io.pigagent.channel;

import io.pigagent.channel.cli.StdinPipeChannel;
import io.pigagent.channel.discord.DiscordChannel;
import io.pigagent.channel.slack.SlackChannel;
import io.pigagent.channel.strategy.StrategyHttpChannel;
import io.pigagent.channel.telegram.TelegramChannel;
import io.pigagent.channel.webhook.WebhookChannel;
import io.pigagent.config.PigAgentConfig.ChannelConfig;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/** Tests the ChannelType enum registry: id lookup, uniqueness, and per-constant construction. */
class ChannelTypeTest {

    private static ChannelConfig cfg() {
        return new ChannelConfig();
    }

    @Test
    void fromIdResolvesKnownAndRejectsUnknown() {
        assertThat(ChannelType.fromId("dingtalk")).contains(ChannelType.DINGTALK);
        assertThat(ChannelType.fromId("feishu")).contains(ChannelType.FEISHU);
        assertThat(ChannelType.fromId("webhook")).contains(ChannelType.WEBHOOK);
        assertThat(ChannelType.fromId("myspace")).isEmpty();
        assertThat(ChannelType.fromId(null)).isEmpty();
    }

    @Test
    void allIdsAreUniqueAndNonBlank() {
        Set<String> ids = new HashSet<>();
        for (ChannelType type : ChannelType.values()) {
            assertThat(type.id()).isNotBlank();
            assertThat(type.displayName()).isNotBlank();
            assertThat(ids.add(type.id())).as("duplicate id %s", type.id()).isTrue();
        }
    }

    @Test
    void createBuildsCorrectAdapterPerConstant() {
        assertThat(ChannelType.TELEGRAM.create(cfg())).isInstanceOf(TelegramChannel.class);
        assertThat(ChannelType.DISCORD.create(cfg())).isInstanceOf(DiscordChannel.class);
        assertThat(ChannelType.WEBHOOK.create(cfg())).isInstanceOf(WebhookChannel.class);
        assertThat(ChannelType.SLACK.create(cfg())).isInstanceOf(SlackChannel.class);
        assertThat(ChannelType.STDIN.create(cfg())).isInstanceOf(StdinPipeChannel.class);
        assertThat(ChannelType.DINGTALK.create(cfg())).isInstanceOf(StrategyHttpChannel.class);
        assertThat(ChannelType.FEISHU.create(cfg())).isInstanceOf(StrategyHttpChannel.class);
    }

    @Test
    void createdChannelReportsConstantIdentity() {
        assertThat(ChannelType.DINGTALK.create(cfg()).channelId()).isEqualTo("dingtalk");
        assertThat(ChannelType.FEISHU.create(cfg()).channelId()).isEqualTo("feishu");
    }

    @Test
    void functionalFlagMarksWorkingChannelsAndStubs() {
        // The working transports (round-trip a conversation).
        assertThat(ChannelType.DINGTALK.isFunctional()).isTrue();
        assertThat(ChannelType.FEISHU.isFunctional()).isTrue();
        assertThat(ChannelType.WEBHOOK.isFunctional()).isTrue();
        assertThat(ChannelType.STDIN.isFunctional()).isTrue();
        // The stubs / reply-less placeholders (must be marked, not pretended-connected).
        assertThat(ChannelType.TELEGRAM.isFunctional()).isFalse();
        assertThat(ChannelType.DISCORD.isFunctional()).isFalse();
        assertThat(ChannelType.SLACK.isFunctional()).isFalse();
    }
}
