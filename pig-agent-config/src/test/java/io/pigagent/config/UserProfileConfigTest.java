package io.pigagent.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * User-profile config ({@code user-profile}, capability {@code user-profile}): default-safe
 * enabled/path/max-chars + a default-off consolidation sub-block, YAML deserialization, and
 * null-setter fault tolerance.
 */
class UserProfileConfigTest {

    private final ObjectMapper yaml = new ObjectMapper(new YAMLFactory());

    @Test
    void defaults_whenNoUserProfileBlock() {
        PigAgentConfig cfg = new PigAgentConfig();
        PigAgentConfig.UserProfileConfig up = cfg.getUserProfile();
        assertThat(up.isEnabled()).isTrue();
        assertThat(up.getPath()).isEqualTo("USER.md");
        assertThat(up.getMaxChars()).isEqualTo(4000);
        assertThat(up.getConsolidation().isEnabled()).isFalse();
        assertThat(up.getConsolidation().getMinGapMinutes()).isEqualTo(60);
        assertThat(up.getConsolidation().getModelId()).isEmpty();
    }

    @Test
    void missingBlock_yieldsDefaults() throws Exception {
        PigAgentConfig cfg = yaml.readValue("agent:\n  name: X\n", PigAgentConfig.class);
        assertThat(cfg.getUserProfile().isEnabled()).isTrue();
        assertThat(cfg.getUserProfile().getPath()).isEqualTo("USER.md");
        assertThat(cfg.getUserProfile().getConsolidation().isEnabled()).isFalse();
    }

    @Test
    void parsesConfiguredValues() throws Exception {
        String src = """
                user-profile:
                  enabled: true
                  path: context/USER.md
                  max-chars: 2000
                  consolidation:
                    enabled: true
                    min-gap-minutes: 120
                    model-id: doubao-lite
                """;
        PigAgentConfig cfg = yaml.readValue(src, PigAgentConfig.class);
        PigAgentConfig.UserProfileConfig up = cfg.getUserProfile();
        assertThat(up.isEnabled()).isTrue();
        assertThat(up.getPath()).isEqualTo("context/USER.md");
        assertThat(up.getMaxChars()).isEqualTo(2000);
        assertThat(up.getConsolidation().isEnabled()).isTrue();
        assertThat(up.getConsolidation().getMinGapMinutes()).isEqualTo(120);
        assertThat(up.getConsolidation().getModelId()).isEqualTo("doubao-lite");
    }

    @Test
    void disabled_parses() throws Exception {
        PigAgentConfig cfg = yaml.readValue("user-profile:\n  enabled: false\n", PigAgentConfig.class);
        assertThat(cfg.getUserProfile().isEnabled()).isFalse();
    }

    @Test
    void nullSettersTolerated() {
        PigAgentConfig cfg = new PigAgentConfig();
        cfg.setUserProfile(null);
        assertThat(cfg.getUserProfile()).isNotNull();

        PigAgentConfig.UserProfileConfig up = new PigAgentConfig.UserProfileConfig();
        up.setPath(null);
        assertThat(up.getPath()).isEqualTo("USER.md");
        up.setPath("  ");
        assertThat(up.getPath()).isEqualTo("USER.md");
        up.setConsolidation(null);
        assertThat(up.getConsolidation()).isNotNull();

        PigAgentConfig.ProfileConsolidationConfig c = new PigAgentConfig.ProfileConsolidationConfig();
        c.setModelId(null);
        assertThat(c.getModelId()).isEmpty();
    }

    @Test
    void consolidation_staysDefaultOff_meSafetyPosture() {
        // M-E: the seeding + hardening mechanism ships, but the enable flag stays false by default
        // (a deliberate safety posture; the one-line flip is deferred to a live-model IT). Locking this
        // default keeps the byte-for-byte "no distillation, no schedule, no LLM" behavior unchanged.
        assertThat(new PigAgentConfig.ProfileConsolidationConfig().isEnabled()).isFalse();
        assertThat(new PigAgentConfig.UserProfileConfig().getConsolidation().isEnabled()).isFalse();
    }
}
