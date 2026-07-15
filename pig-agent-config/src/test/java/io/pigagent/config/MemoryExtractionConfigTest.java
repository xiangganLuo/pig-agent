package io.pigagent.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** memory.extraction 配置：默认禁用/阈值 0.7/去抖 2000；YAML 反序列化；容错。 */
class MemoryExtractionConfigTest {

    private final ObjectMapper yaml = new ObjectMapper(new YAMLFactory());

    @Test
    void defaults_whenNoMemoryBlock() {
        PigAgentConfig.MemoryExtractionConfig e = new PigAgentConfig().getMemory().getExtraction();
        assertThat(e.isEnabled()).isFalse();
        assertThat(e.getConfidenceThreshold()).isEqualTo(0.7);
        assertThat(e.getDebounceMs()).isEqualTo(2000L);
    }

    @Test
    void missingBlock_yieldsDefaults() throws Exception {
        PigAgentConfig cfg = yaml.readValue("agent:\n  name: X\n", PigAgentConfig.class);
        assertThat(cfg.getMemory().getExtraction().isEnabled()).isFalse();
        assertThat(cfg.getMemory().getExtraction().getConfidenceThreshold()).isEqualTo(0.7);
    }

    @Test
    void parsesConfiguredValues() throws Exception {
        String src = """
                memory:
                  extraction:
                    enabled: true
                    confidence-threshold: 0.85
                    debounce-ms: 500
                """;
        PigAgentConfig cfg = yaml.readValue(src, PigAgentConfig.class);
        PigAgentConfig.MemoryExtractionConfig e = cfg.getMemory().getExtraction();
        assertThat(e.isEnabled()).isTrue();
        assertThat(e.getConfidenceThreshold()).isEqualTo(0.85);
        assertThat(e.getDebounceMs()).isEqualTo(500L);
    }

    @Test
    void nullSettersTolerated() {
        PigAgentConfig cfg = new PigAgentConfig();
        cfg.setMemory(null);
        assertThat(cfg.getMemory()).isNotNull();
        assertThat(cfg.getMemory().getExtraction()).isNotNull();

        PigAgentConfig.MemoryConfig m = new PigAgentConfig.MemoryConfig();
        m.setExtraction(null);
        assertThat(m.getExtraction()).isNotNull();
    }
}
