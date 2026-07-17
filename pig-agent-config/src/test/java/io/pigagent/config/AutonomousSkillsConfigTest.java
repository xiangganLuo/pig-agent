package io.pigagent.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** skills.autonomous 配置：默认关闭/暂存 .pending/不自动提升；YAML 反序列化；容错。 */
class AutonomousSkillsConfigTest {

    private final ObjectMapper yaml = new ObjectMapper(new YAMLFactory());

    @Test
    void defaults_whenNoSkillsBlock() {
        PigAgentConfig.AutonomousSkillsConfig a = new PigAgentConfig().getSkills().getAutonomous();
        assertThat(a.isEnabled()).isFalse();
        assertThat(a.getStagingDir()).isEqualTo(".pending");
        assertThat(a.isAutoPromote()).isFalse();
    }

    @Test
    void missingBlock_yieldsDefaults() throws Exception {
        PigAgentConfig cfg = yaml.readValue("agent:\n  name: X\n", PigAgentConfig.class);
        assertThat(cfg.getSkills().getAutonomous().isEnabled()).isFalse();
        assertThat(cfg.getSkills().getAutonomous().getStagingDir()).isEqualTo(".pending");
    }

    @Test
    void parsesConfiguredValues() throws Exception {
        String src = """
                skills:
                  autonomous:
                    enabled: true
                    staging-dir: .drafts
                    auto-promote: true
                """;
        PigAgentConfig cfg = yaml.readValue(src, PigAgentConfig.class);
        PigAgentConfig.AutonomousSkillsConfig a = cfg.getSkills().getAutonomous();
        assertThat(a.isEnabled()).isTrue();
        assertThat(a.getStagingDir()).isEqualTo(".drafts");
        assertThat(a.isAutoPromote()).isTrue();
    }

    @Test
    void blankStagingDir_normalizesToDefault() {
        PigAgentConfig.AutonomousSkillsConfig a = new PigAgentConfig.AutonomousSkillsConfig();
        a.setStagingDir("  ");
        assertThat(a.getStagingDir()).isEqualTo(".pending");
        a.setStagingDir(null);
        assertThat(a.getStagingDir()).isEqualTo(".pending");
    }

    @Test
    void nullSettersTolerated() {
        PigAgentConfig cfg = new PigAgentConfig();
        cfg.setSkills(null);
        assertThat(cfg.getSkills()).isNotNull();
        assertThat(cfg.getSkills().getAutonomous()).isNotNull();

        PigAgentConfig.SkillsConfig s = new PigAgentConfig.SkillsConfig();
        s.setAutonomous(null);
        assertThat(s.getAutonomous()).isNotNull();
    }
}
