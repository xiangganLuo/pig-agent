package io.pigagent.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class RetryConfigTest {

    private final ObjectMapper yaml = new ObjectMapper(new YAMLFactory());

    @Test
    void defaults_whenNoRetryBlock() {
        // Arrange + Act
        PigAgentConfig.RetryConfig retry = new PigAgentConfig.ModelConfig().getRetry();

        // Assert — enabled with the agreed defaults
        assertThat(retry.isEnabled()).isTrue();
        assertThat(retry.getMaxRetries()).isEqualTo(10);
        assertThat(retry.getPerAttemptTimeoutSeconds()).isEqualTo(0); // disabled by default (unsafe w/ non-interruptible agent)
        assertThat(retry.getFirstBackoffMs()).isEqualTo(500);
        assertThat(retry.getMaxBackoffMs()).isEqualTo(8000);
    }

    @Test
    void missingRetryBlock_yieldsDefaults() throws Exception {
        PigAgentConfig cfg = yaml.readValue("model:\n  provider: anthropic\n", PigAgentConfig.class);
        assertThat(cfg.getModel().getRetry().isEnabled()).isTrue();
        assertThat(cfg.getModel().getRetry().getMaxRetries()).isEqualTo(10);
    }

    @Test
    void parsesConfiguredValues() throws Exception {
        String src = """
                model:
                  provider: anthropic
                  retry:
                    enabled: false
                    max-retries: 3
                    per-attempt-timeout-seconds: 5
                    first-backoff-ms: 200
                    max-backoff-ms: 4000
                """;
        PigAgentConfig cfg = yaml.readValue(src, PigAgentConfig.class);
        PigAgentConfig.RetryConfig retry = cfg.getModel().getRetry();
        assertThat(retry.isEnabled()).isFalse();
        assertThat(retry.getMaxRetries()).isEqualTo(3);
        assertThat(retry.getPerAttemptTimeoutSeconds()).isEqualTo(5);
        assertThat(retry.getFirstBackoffMs()).isEqualTo(200);
        assertThat(retry.getMaxBackoffMs()).isEqualTo(4000);
    }
}
