package io.pigagent.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Native memory config ({@code pa-memory-native}): default-safe flush/consolidation/model-id, YAML
 * deserialization, and fault tolerance. The master on/off switch stays the top-level
 * {@code memory-enabled} (default true).
 */
class MemoryConfigTest {

    private final ObjectMapper yaml = new ObjectMapper(new YAMLFactory());

    @Test
    void defaults_whenNoMemoryBlock() {
        PigAgentConfig cfg = new PigAgentConfig();
        PigAgentConfig.MemoryConfig m = cfg.getMemory();
        assertThat(cfg.isMemoryEnabled()).isTrue(); // master switch default on
        assertThat(m.getFlush()).isEqualTo("always");
        assertThat(m.getFlushThrottleMinutes()).isZero();
        assertThat(m.getConsolidationMinGapMinutes()).isEqualTo(30);
        assertThat(m.getConsolidationMaxTokens()).isEqualTo(4000);
        assertThat(m.getModelId()).isEmpty();
    }

    @Test
    void missingBlock_yieldsDefaults() throws Exception {
        PigAgentConfig cfg = yaml.readValue("agent:\n  name: X\n", PigAgentConfig.class);
        assertThat(cfg.getMemory().getFlush()).isEqualTo("always");
        assertThat(cfg.getMemory().getConsolidationMinGapMinutes()).isEqualTo(30);
    }

    @Test
    void parsesConfiguredValues() throws Exception {
        String src = """
                memory-enabled: true
                memory:
                  flush: throttled
                  flush-throttle-minutes: 5
                  consolidation-min-gap-minutes: 60
                  consolidation-max-tokens: 8000
                  model-id: doubao-lite
                """;
        PigAgentConfig cfg = yaml.readValue(src, PigAgentConfig.class);
        PigAgentConfig.MemoryConfig m = cfg.getMemory();
        assertThat(m.getFlush()).isEqualTo("throttled");
        assertThat(m.getFlushThrottleMinutes()).isEqualTo(5);
        assertThat(m.getConsolidationMinGapMinutes()).isEqualTo(60);
        assertThat(m.getConsolidationMaxTokens()).isEqualTo(8000);
        assertThat(m.getModelId()).isEqualTo("doubao-lite");
    }

    @Test
    void nullSettersTolerated() {
        PigAgentConfig cfg = new PigAgentConfig();
        cfg.setMemory(null);
        assertThat(cfg.getMemory()).isNotNull();

        PigAgentConfig.MemoryConfig m = new PigAgentConfig.MemoryConfig();
        m.setFlush(null);
        assertThat(m.getFlush()).isEqualTo("always");
        m.setModelId(null);
        assertThat(m.getModelId()).isEmpty();
    }
}
