package io.pigagent.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * skills.curator 配置（skill-curator-and-graded-promotion S3）：默认关闭 + 先只读（auto-archive=false /
 * umbrella-pass-mode=dry_run_only / canary 关）；YAML 反序列化；null/缺块容错；非法数值 clamp。
 */
class CuratorConfigTest {

    private final ObjectMapper yaml = new ObjectMapper(new YAMLFactory());

    @Test
    void defaults_disabledAndReadOnly_whenNoBlock() {
        PigAgentConfig.CuratorConfig c = new PigAgentConfig().getSkills().getCurator();
        assertThat(c.isEnabled()).isFalse();
        assertThat(c.isUsageRecording()).isTrue();
        assertThat(c.isAutoArchive()).isFalse();
        assertThat(c.getUmbrellaPassMode()).isEqualTo("dry_run_only");
        assertThat(c.getSchedule()).isEqualTo("0 3 * * 0");
        assertThat(c.getStaleAfterDays()).isEqualTo(30);
        assertThat(c.getArchiveAfterDays()).isEqualTo(90);
        assertThat(c.getMinIdleHours()).isEqualTo(2);
        assertThat(c.getBackupRetention()).isEqualTo(3);
        assertThat(c.getCanary().isEnabled()).isFalse();
        assertThat(c.getCanary().getPercent()).isEqualTo(10);
    }

    @Test
    void missingBlock_yieldsDefaults() throws Exception {
        PigAgentConfig cfg = yaml.readValue("agent:\n  name: X\n", PigAgentConfig.class);
        assertThat(cfg.getSkills().getCurator().isEnabled()).isFalse();
        assertThat(cfg.getSkills().getCurator().isAutoArchive()).isFalse();
    }

    @Test
    void parsesConfiguredValues() throws Exception {
        String src = """
                skills:
                  curator:
                    enabled: true
                    usage-recording: false
                    schedule: "0 4 * * *"
                    stale-after-days: 14
                    archive-after-days: 45
                    min-idle-hours: 6
                    auto-archive: true
                    umbrella-pass-mode: live
                    backup-retention: 5
                    canary:
                      enabled: true
                      percent: 25
                      ramp-up-days: 3
                """;
        PigAgentConfig cfg = yaml.readValue(src, PigAgentConfig.class);
        PigAgentConfig.CuratorConfig c = cfg.getSkills().getCurator();
        assertThat(c.isEnabled()).isTrue();
        assertThat(c.isUsageRecording()).isFalse();
        assertThat(c.getSchedule()).isEqualTo("0 4 * * *");
        assertThat(c.getStaleAfterDays()).isEqualTo(14);
        assertThat(c.getArchiveAfterDays()).isEqualTo(45);
        assertThat(c.getMinIdleHours()).isEqualTo(6);
        assertThat(c.isAutoArchive()).isTrue();
        assertThat(c.getUmbrellaPassMode()).isEqualTo("live");
        assertThat(c.getBackupRetention()).isEqualTo(5);
        assertThat(c.getCanary().isEnabled()).isTrue();
        assertThat(c.getCanary().getPercent()).isEqualTo(25);
        assertThat(c.getCanary().getRampUpDays()).isEqualTo(3);
    }

    @Test
    void nullSettersTolerated_andIllegalValuesClamped() {
        PigAgentConfig.SkillsConfig s = new PigAgentConfig.SkillsConfig();
        s.setCurator(null);
        assertThat(s.getCurator()).isNotNull();
        assertThat(s.getCurator().isEnabled()).isFalse();

        PigAgentConfig.CuratorConfig c = new PigAgentConfig.CuratorConfig();
        c.setSchedule(null);
        assertThat(c.getSchedule()).isEqualTo("0 3 * * 0");
        c.setSchedule("  ");
        assertThat(c.getSchedule()).isEqualTo("0 3 * * 0");
        c.setUmbrellaPassMode(null);
        assertThat(c.getUmbrellaPassMode()).isEqualTo("dry_run_only");
        c.setStaleAfterDays(0);
        assertThat(c.getStaleAfterDays()).isEqualTo(1);
        c.setArchiveAfterDays(-5);
        assertThat(c.getArchiveAfterDays()).isEqualTo(1);
        c.setMinIdleHours(-3);
        assertThat(c.getMinIdleHours()).isEqualTo(0);
        c.setBackupRetention(-1);
        assertThat(c.getBackupRetention()).isEqualTo(0);
        c.setCanary(null);
        assertThat(c.getCanary()).isNotNull();

        PigAgentConfig.CanaryConfig cc = new PigAgentConfig.CanaryConfig();
        cc.setPercent(250);
        assertThat(cc.getPercent()).isEqualTo(100);
        cc.setPercent(-5);
        assertThat(cc.getPercent()).isEqualTo(0);
        cc.setRampUpDays(-2);
        assertThat(cc.getRampUpDays()).isEqualTo(0);
    }
}
