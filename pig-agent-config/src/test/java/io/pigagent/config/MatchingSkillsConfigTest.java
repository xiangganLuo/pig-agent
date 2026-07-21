package io.pigagent.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** skills.matching 配置（skill-matching S2）：默认关闭；YAML 反序列化；缺块/非法值容错 clamp。 */
class MatchingSkillsConfigTest {

    private final ObjectMapper yaml = new ObjectMapper(new YAMLFactory());

    @Test
    void defaults_disabled_safeValues() {
        PigAgentConfig.MatchingSkillsConfig m = new PigAgentConfig().getSkills().getMatching();
        assertThat(m.isEnabled()).isFalse();
        assertThat(m.getTopK()).isEqualTo(10);
        assertThat(m.getMinScore()).isEqualTo(0.0);
    }

    @Test
    void missingBlock_yieldsDefaults() throws Exception {
        PigAgentConfig cfg = yaml.readValue("agent:\n  name: X\n", PigAgentConfig.class);
        PigAgentConfig.MatchingSkillsConfig m = cfg.getSkills().getMatching();
        assertThat(m.isEnabled()).isFalse();
        assertThat(m.getTopK()).isEqualTo(10);
        assertThat(m.getMinScore()).isEqualTo(0.0);
    }

    @Test
    void parsesConfiguredValues() throws Exception {
        String src = """
                skills:
                  matching:
                    enabled: true
                    top-k: 5
                    min-score: 0.2
                """;
        PigAgentConfig cfg = yaml.readValue(src, PigAgentConfig.class);
        PigAgentConfig.MatchingSkillsConfig m = cfg.getSkills().getMatching();
        assertThat(m.isEnabled()).isTrue();
        assertThat(m.getTopK()).isEqualTo(5);
        assertThat(m.getMinScore()).isEqualTo(0.2);
    }

    @Test
    void illegalValues_clampToDefaults() {
        PigAgentConfig.MatchingSkillsConfig m = new PigAgentConfig.MatchingSkillsConfig();
        m.setTopK(0);
        m.setMinScore(-1.0);
        assertThat(m.getTopK()).isEqualTo(10);
        assertThat(m.getMinScore()).isEqualTo(0.0);
    }

    @Test
    void nullSetterTolerated() {
        PigAgentConfig.SkillsConfig s = new PigAgentConfig.SkillsConfig();
        s.setMatching(null);
        assertThat(s.getMatching()).isNotNull();
        assertThat(s.getMatching().isEnabled()).isFalse();
    }
}
