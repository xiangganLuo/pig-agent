package io.pigagent.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Covers the {@code model} block, focusing on the optional {@code fallback-model-id} resilience knob
 * added for native model failover — its default and its YAML round-trip.
 */
class ModelConfigTest {

    private final ObjectMapper yaml = new ObjectMapper(new YAMLFactory());

    @Test
    void fallbackModelId_defaultsToBlank() {
        // Arrange + Act
        PigAgentConfig.ModelConfig model = new PigAgentConfig.ModelConfig();

        // Assert — no fallback configured out of the box (today's behavior)
        assertThat(model.getFallbackModelId()).isEmpty();
    }

    @Test
    void fallbackModelId_nullSetterCoercesToBlank() {
        PigAgentConfig.ModelConfig model = new PigAgentConfig.ModelConfig();
        model.setFallbackModelId(null);
        assertThat(model.getFallbackModelId()).isEmpty();
    }

    @Test
    void fallbackModelId_roundTripsThroughYaml() throws Exception {
        // Arrange
        String src = """
                model:
                  provider: anthropic
                  fallback-model-id: backup-1
                """;

        // Act
        PigAgentConfig cfg = yaml.readValue(src, PigAgentConfig.class);

        // Assert
        assertThat(cfg.getModel().getFallbackModelId()).isEqualTo("backup-1");
    }

    @Test
    void missingModelBlock_yieldsBlankFallback() throws Exception {
        PigAgentConfig cfg = yaml.readValue("agent:\n  name: PigAgent\n", PigAgentConfig.class);
        assertThat(cfg.getModel().getFallbackModelId()).isEmpty();
    }
}
