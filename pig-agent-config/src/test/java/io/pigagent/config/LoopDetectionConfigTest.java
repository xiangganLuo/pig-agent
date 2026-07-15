package io.pigagent.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class LoopDetectionConfigTest {

    private final ObjectMapper yaml = new ObjectMapper(new YAMLFactory());

    @Test
    void defaults_whenNoLoopDetectionBlock() {
        PigAgentConfig.LoopDetectionConfig lc = new PigAgentConfig().getLoopDetection();

        assertThat(lc.isEnabled()).isTrue();
        assertThat(lc.getWindowSize()).isEqualTo(20);
        assertThat(lc.getWarnThreshold()).isEqualTo(3);
        assertThat(lc.getStopThreshold()).isEqualTo(5);
    }

    @Test
    void missingBlock_inYaml_yieldsDefaults() throws Exception {
        PigAgentConfig cfg = yaml.readValue("agent:\n  name: PigAgent\n", PigAgentConfig.class);

        assertThat(cfg.getLoopDetection().isEnabled()).isTrue();
        assertThat(cfg.getLoopDetection().getWindowSize()).isEqualTo(20);
    }

    @Test
    void parsesConfiguredValues() throws Exception {
        String src = """
                loop-detection:
                  enabled: false
                  window-size: 8
                  warn-threshold: 2
                  stop-threshold: 4
                """;
        PigAgentConfig cfg = yaml.readValue(src, PigAgentConfig.class);
        PigAgentConfig.LoopDetectionConfig lc = cfg.getLoopDetection();

        assertThat(lc.isEnabled()).isFalse();
        assertThat(lc.getWindowSize()).isEqualTo(8);
        assertThat(lc.getWarnThreshold()).isEqualTo(2);
        assertThat(lc.getStopThreshold()).isEqualTo(4);
    }
}
