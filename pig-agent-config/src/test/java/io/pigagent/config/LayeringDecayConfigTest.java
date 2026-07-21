package io.pigagent.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Config coverage for {@code memory.layering-decay} + {@code memory.daily-file-retention-days}
 * (capability {@code memory-layering-and-decay}, M-C): default-safe (off + retention 0 = today's
 * behavior), YAML deserialization, missing-block defaults, clamp, and null-tolerant setters.
 */
class LayeringDecayConfigTest {

    private final ObjectMapper yaml = new ObjectMapper(new YAMLFactory());

    @Test
    void defaults_areOffAndDailyRetentionZero() {
        PigAgentConfig.MemoryConfig m = new PigAgentConfig().getMemory();
        assertThat(m.getDailyFileRetentionDays()).isZero(); // 0 → don't call → native default (90)
        PigAgentConfig.LayeringDecayConfig ld = m.getLayeringDecay();
        assertThat(ld.isEnabled()).isFalse();
        assertThat(ld.isAutoArchive()).isFalse();
        assertThat(ld.getStaleAfterDays()).isEqualTo(30);
        assertThat(ld.getArchiveAfterDays()).isEqualTo(90);
        assertThat(ld.getReinforceAccessThreshold()).isEqualTo(2);
        assertThat(ld.getPromoteAccessThreshold()).isEqualTo(5);
        assertThat(ld.getMinGapMinutes()).isEqualTo(60);
    }

    @Test
    void missingBlock_yieldsDefaults() throws Exception {
        PigAgentConfig cfg = yaml.readValue("memory:\n  flush: always\n", PigAgentConfig.class);
        assertThat(cfg.getMemory().getDailyFileRetentionDays()).isZero();
        assertThat(cfg.getMemory().getLayeringDecay().isEnabled()).isFalse();
    }

    @Test
    void parsesConfiguredValues() throws Exception {
        String src = """
                memory:
                  daily-file-retention-days: 120
                  layering-decay:
                    enabled: true
                    auto-archive: true
                    stale-after-days: 45
                    archive-after-days: 180
                    reinforce-access-threshold: 3
                    promote-access-threshold: 8
                    min-gap-minutes: 240
                """;
        PigAgentConfig.MemoryConfig m = yaml.readValue(src, PigAgentConfig.class).getMemory();
        assertThat(m.getDailyFileRetentionDays()).isEqualTo(120);
        PigAgentConfig.LayeringDecayConfig ld = m.getLayeringDecay();
        assertThat(ld.isEnabled()).isTrue();
        assertThat(ld.isAutoArchive()).isTrue();
        assertThat(ld.getStaleAfterDays()).isEqualTo(45);
        assertThat(ld.getArchiveAfterDays()).isEqualTo(180);
        assertThat(ld.getReinforceAccessThreshold()).isEqualTo(3);
        assertThat(ld.getPromoteAccessThreshold()).isEqualTo(8);
        assertThat(ld.getMinGapMinutes()).isEqualTo(240);
    }

    @Test
    void thresholdsClampToSaneMinimums() {
        PigAgentConfig.LayeringDecayConfig ld = new PigAgentConfig.LayeringDecayConfig();
        ld.setStaleAfterDays(0);
        assertThat(ld.getStaleAfterDays()).isEqualTo(1);
        ld.setMinGapMinutes(0);
        assertThat(ld.getMinGapMinutes()).isEqualTo(1);
        ld.setReinforceAccessThreshold(-3);
        assertThat(ld.getReinforceAccessThreshold()).isEqualTo(1);
        // archive clamps to >= stale; promote clamps to >= reinforce.
        ld.setStaleAfterDays(50);
        ld.setArchiveAfterDays(10);
        assertThat(ld.getArchiveAfterDays()).isEqualTo(50);
        ld.setReinforceAccessThreshold(4);
        ld.setPromoteAccessThreshold(1);
        assertThat(ld.getPromoteAccessThreshold()).isEqualTo(4);
    }

    @Test
    void nullSetterTolerated() {
        PigAgentConfig.MemoryConfig m = new PigAgentConfig.MemoryConfig();
        m.setLayeringDecay(null);
        assertThat(m.getLayeringDecay()).isNotNull();
    }
}
