package io.pigagent.channel;

import io.pigagent.channel.cli.StdinPipeChannel;
import io.pigagent.channel.slack.SlackChannel;
import io.pigagent.channel.strategy.StrategyHttpChannel;
import io.pigagent.channel.webhook.WebhookChannel;
import io.pigagent.config.PigAgentConfig.ChannelConfig;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** Tests channel construction + the enable gate. */
class ChannelFactoryTest {

    private final ChannelFactory factory = new ChannelFactory();

    private static ChannelConfig enabled() {
        ChannelConfig c = new ChannelConfig();
        c.setEnabled(true);
        return c;
    }

    @Test
    void disabledConfigProducesNoChannel() {
        assertThat(factory.create("webhook", new ChannelConfig())).isEmpty();
    }

    @Test
    void nullConfigOrIdProducesNoChannel() {
        assertThat(factory.create("webhook", null)).isEmpty();
        assertThat(factory.create(null, enabled())).isEmpty();
    }

    @Test
    void unknownEnabledIdIsSkipped() {
        assertThat(factory.create("myspace", enabled())).isEmpty();
    }

    @Test
    void knownEnabledIdsBuildCorrectAdapter() {
        assertThat(factory.create("webhook", enabled())).get().isInstanceOf(WebhookChannel.class);
        assertThat(factory.create("slack", enabled())).get().isInstanceOf(SlackChannel.class);
        assertThat(factory.create("stdin", enabled())).get().isInstanceOf(StdinPipeChannel.class);
        assertThat(factory.create("dingtalk", enabled())).get().isInstanceOf(StrategyHttpChannel.class);
        assertThat(factory.create("feishu", enabled())).get().isInstanceOf(StrategyHttpChannel.class);
    }

    @Test
    void newRobotChannelsReportTheirIds() {
        assertThat(factory.create("dingtalk", enabled())).get()
                .extracting(Channel::channelId).isEqualTo("dingtalk");
        assertThat(factory.create("feishu", enabled())).get()
                .extracting(Channel::channelId).isEqualTo("feishu");
    }

    @Test
    void createEnabledReturnsOnlyEnabledKnownChannels() {
        // Arrange
        Map<String, ChannelConfig> configs = new LinkedHashMap<>();
        configs.put("webhook", enabled());
        configs.put("slack", new ChannelConfig()); // disabled
        configs.put("bogus", enabled());             // unknown

        // Act
        List<Channel> channels = factory.createEnabled(configs);

        // Assert
        assertThat(channels).singleElement().isInstanceOf(WebhookChannel.class);
    }

    @Test
    void createEnabledOnNullMapIsEmpty() {
        assertThat(factory.createEnabled(null)).isEmpty();
    }
}
