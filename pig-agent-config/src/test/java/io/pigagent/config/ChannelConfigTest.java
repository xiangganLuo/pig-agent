package io.pigagent.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** ChannelConfig 的纯单测（缺省安全 + YAML 往返 + 旧配置向后兼容）。 */
class ChannelConfigTest {

    private final ObjectMapper yaml = new ObjectMapper(new YAMLFactory());

    @Test
    void defaultsAreSafeAndDisabled() {
        // Arrange
        PigAgentConfig.ChannelConfig c = new PigAgentConfig.ChannelConfig();

        // Assert
        assertThat(c.isEnabled()).isFalse();
        assertThat(c.getToken()).isNull();
        assertThat(c.getPath()).isNull();
        assertThat(c.getSigningSecret()).isNull();
        assertThat(c.getPort()).isZero();
        assertThat(c.getWebhookUrl()).isNull();
        assertThat(c.getSignSecret()).isNull();
        assertThat(c.getVerificationToken()).isNull();
    }

    @Test
    void settersRoundTrip() {
        // Arrange
        PigAgentConfig.ChannelConfig c = new PigAgentConfig.ChannelConfig();

        // Act
        c.setEnabled(true);
        c.setToken("t");
        c.setPort(8686);
        c.setPath("/hook");
        c.setSigningSecret("s");
        c.setWebhookUrl("https://example.com/robot");
        c.setSignSecret("ss");
        c.setVerificationToken("vt");

        // Assert
        assertThat(c.isEnabled()).isTrue();
        assertThat(c.getToken()).isEqualTo("t");
        assertThat(c.getPort()).isEqualTo(8686);
        assertThat(c.getPath()).isEqualTo("/hook");
        assertThat(c.getSigningSecret()).isEqualTo("s");
        assertThat(c.getWebhookUrl()).isEqualTo("https://example.com/robot");
        assertThat(c.getSignSecret()).isEqualTo("ss");
        assertThat(c.getVerificationToken()).isEqualTo("vt");
    }

    @Test
    void yamlRoundTripReadsExtendedFields() throws Exception {
        // Arrange
        String doc = """
                channels:
                  webhook:
                    enabled: true
                    port: 9000
                    path: /webhook
                    token: secret-token
                  slack:
                    enabled: true
                    signing-secret: shhh
                """;

        // Act
        PigAgentConfig cfg = yaml.readValue(doc, PigAgentConfig.class);
        PigAgentConfig.ChannelConfig webhook = cfg.getChannels().get("webhook");
        PigAgentConfig.ChannelConfig slack = cfg.getChannels().get("slack");

        // Assert
        assertThat(webhook.isEnabled()).isTrue();
        assertThat(webhook.getPort()).isEqualTo(9000);
        assertThat(webhook.getPath()).isEqualTo("/webhook");
        assertThat(webhook.getToken()).isEqualTo("secret-token");
        assertThat(slack.isEnabled()).isTrue();
        assertThat(slack.getSigningSecret()).isEqualTo("shhh");
    }

    @Test
    void yamlRoundTripReadsRobotChannelFields() throws Exception {
        // Arrange
        String doc = """
                channels:
                  dingtalk:
                    enabled: true
                    webhook-url: https://oapi.dingtalk.com/robot/send?access_token=T
                    sign-secret: ding-secret
                  feishu:
                    enabled: true
                    webhook-url: https://open.feishu.cn/open-apis/bot/v2/hook/XYZ
                    sign-secret: lark-secret
                    verification-token: verif-token
                    port: 9100
                    path: /lark
                """;

        // Act
        PigAgentConfig cfg = yaml.readValue(doc, PigAgentConfig.class);
        PigAgentConfig.ChannelConfig dingtalk = cfg.getChannels().get("dingtalk");
        PigAgentConfig.ChannelConfig feishu = cfg.getChannels().get("feishu");

        // Assert
        assertThat(dingtalk.isEnabled()).isTrue();
        assertThat(dingtalk.getWebhookUrl()).contains("oapi.dingtalk.com");
        assertThat(dingtalk.getSignSecret()).isEqualTo("ding-secret");
        assertThat(feishu.getWebhookUrl()).contains("open.feishu.cn");
        assertThat(feishu.getSignSecret()).isEqualTo("lark-secret");
        assertThat(feishu.getVerificationToken()).isEqualTo("verif-token");
        assertThat(feishu.getPort()).isEqualTo(9100);
        assertThat(feishu.getPath()).isEqualTo("/lark");
    }

    @Test
    void legacyConfigWithOnlyEnabledAndTokenStillParses() throws Exception {
        // Arrange：旧配置只含 enabled/token
        String doc = """
                channels:
                  telegram:
                    enabled: true
                    token: abc
                """;

        // Act
        PigAgentConfig cfg = yaml.readValue(doc, PigAgentConfig.class);
        PigAgentConfig.ChannelConfig telegram = cfg.getChannels().get("telegram");

        // Assert：新字段取安全缺省
        assertThat(telegram.isEnabled()).isTrue();
        assertThat(telegram.getToken()).isEqualTo("abc");
        assertThat(telegram.getPort()).isZero();
        assertThat(telegram.getPath()).isNull();
        assertThat(telegram.getSigningSecret()).isNull();
    }
}
