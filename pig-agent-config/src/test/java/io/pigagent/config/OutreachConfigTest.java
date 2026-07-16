package io.pigagent.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** outreach 配置：默认关闭 + 子块安全缺省；YAML 反序列化；旧配置向后兼容；容错 setter。 */
class OutreachConfigTest {

    private final ObjectMapper yaml = new ObjectMapper(new YAMLFactory());

    @Test
    void defaultsWhenNoOutreachBlock() {
        PigAgentConfig.OutreachConfig o = new PigAgentConfig().getOutreach();
        assertThat(o.isEnabled()).isFalse();
        assertThat(o.getChannel()).isEmpty();
        assertThat(o.getRecipient()).isEmpty();
        assertThat(o.getQuietHours().isEnabled()).isFalse();
        assertThat(o.getRateLimit().getMaxPerWindow()).isEqualTo(5);
        assertThat(o.getRateLimit().getWindowMinutes()).isEqualTo(60);
        assertThat(o.getDedupWindowMinutes()).isEqualTo(30);
        assertThat(o.getBriefing().isEnabled()).isFalse();
        assertThat(o.getReportPush().isEnabled()).isFalse();
    }

    @Test
    void oldConfigWithoutOutreachStillParses() throws Exception {
        PigAgentConfig cfg = yaml.readValue("agent:\n  name: X\n", PigAgentConfig.class);
        assertThat(cfg.getOutreach()).isNotNull();
        assertThat(cfg.getOutreach().isEnabled()).isFalse();
    }

    @Test
    void parsesConfiguredValues() throws Exception {
        String src = """
                outreach:
                  enabled: true
                  channel: feishu
                  recipient: boss
                  quiet-hours:
                    enabled: true
                    start: "23:00"
                    end: "07:30"
                  rate-limit:
                    max-per-window: 3
                    window-minutes: 30
                  dedup-window-minutes: 15
                  briefing:
                    enabled: true
                    cron: "0 8 * * *"
                    title: 简报
                    body: 内容
                  report-push:
                    enabled: true
                """;
        PigAgentConfig.OutreachConfig o = yaml.readValue(src, PigAgentConfig.class).getOutreach();

        assertThat(o.isEnabled()).isTrue();
        assertThat(o.getChannel()).isEqualTo("feishu");
        assertThat(o.getRecipient()).isEqualTo("boss");
        assertThat(o.getQuietHours().isEnabled()).isTrue();
        assertThat(o.getQuietHours().getStart()).isEqualTo("23:00");
        assertThat(o.getQuietHours().getEnd()).isEqualTo("07:30");
        assertThat(o.getRateLimit().getMaxPerWindow()).isEqualTo(3);
        assertThat(o.getRateLimit().getWindowMinutes()).isEqualTo(30);
        assertThat(o.getDedupWindowMinutes()).isEqualTo(15);
        assertThat(o.getBriefing().isEnabled()).isTrue();
        assertThat(o.getBriefing().getCron()).isEqualTo("0 8 * * *");
        assertThat(o.getReportPush().isEnabled()).isTrue();
    }

    @Test
    void nullSettersTolerated() {
        PigAgentConfig cfg = new PigAgentConfig();
        cfg.setOutreach(null);
        assertThat(cfg.getOutreach()).isNotNull();

        PigAgentConfig.OutreachConfig o = new PigAgentConfig.OutreachConfig();
        o.setQuietHours(null);
        o.setRateLimit(null);
        o.setBriefing(null);
        o.setReportPush(null);
        assertThat(o.getQuietHours()).isNotNull();
        assertThat(o.getRateLimit()).isNotNull();
        assertThat(o.getBriefing()).isNotNull();
        assertThat(o.getReportPush()).isNotNull();
    }
}
