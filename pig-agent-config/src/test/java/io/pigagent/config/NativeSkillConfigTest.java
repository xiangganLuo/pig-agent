package io.pigagent.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** skills.native 配置（native-skill-engine-bridge S1）：默认关闭；YAML 反序列化；容错。 */
class NativeSkillConfigTest {

    private final ObjectMapper yaml = new ObjectMapper(new YAMLFactory());

    @Test
    void defaults_disabled_whenNoBlock() {
        PigAgentConfig.NativeSkillConfig n = new PigAgentConfig().getSkills().getNative();
        assertThat(n.isEnabled()).isFalse();
        assertThat(n.getClasspathResourceDir()).isEmpty();
    }

    @Test
    void missingBlock_yieldsDefaults() throws Exception {
        PigAgentConfig cfg = yaml.readValue("agent:\n  name: X\n", PigAgentConfig.class);
        assertThat(cfg.getSkills().getNative().isEnabled()).isFalse();
        assertThat(cfg.getSkills().getNative().getClasspathResourceDir()).isEmpty();
    }

    @Test
    void parsesConfiguredValues() throws Exception {
        String src = """
                skills:
                  native:
                    enabled: true
                    classpath-resource-dir: skills
                """;
        PigAgentConfig cfg = yaml.readValue(src, PigAgentConfig.class);
        PigAgentConfig.NativeSkillConfig n = cfg.getSkills().getNative();
        assertThat(n.isEnabled()).isTrue();
        assertThat(n.getClasspathResourceDir()).isEqualTo("skills");
    }

    @Test
    void nullSettersTolerated() {
        PigAgentConfig.SkillsConfig s = new PigAgentConfig.SkillsConfig();
        s.setNative(null);
        assertThat(s.getNative()).isNotNull();
        assertThat(s.getNative().isEnabled()).isFalse();

        PigAgentConfig.NativeSkillConfig n = new PigAgentConfig.NativeSkillConfig();
        n.setClasspathResourceDir(null);
        assertThat(n.getClasspathResourceDir()).isEmpty();
    }
}
