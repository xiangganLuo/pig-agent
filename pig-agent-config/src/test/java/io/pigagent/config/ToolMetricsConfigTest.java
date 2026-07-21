package io.pigagent.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@code tools.metrics} (tools-observability, T3) config defaults + parse. Default-safe: enabled by
 * default (metrics are pure-observation additive), overridable to off.
 */
class ToolMetricsConfigTest {

    private static final ObjectMapper YAML = new ObjectMapper(new YAMLFactory());

    @Test
    void defaultsToEnabled() {
        PigAgentConfig.ToolMetricsConfig m = new PigAgentConfig().getTools().getMetrics();
        assertThat(m).isNotNull();
        assertThat(m.isEnabled()).isTrue();
    }

    @Test
    void parsesDisabledOverride() throws Exception {
        PigAgentConfig cfg = YAML.readValue("""
                tools:
                  metrics:
                    enabled: false
                """, PigAgentConfig.class);
        assertThat(cfg.getTools().getMetrics().isEnabled()).isFalse();
    }

    @Test
    void missingBlockKeepsDefaults() throws Exception {
        PigAgentConfig cfg = YAML.readValue("agent:\n  name: X\n", PigAgentConfig.class);
        assertThat(cfg.getTools().getMetrics().isEnabled()).isTrue();
    }
}
