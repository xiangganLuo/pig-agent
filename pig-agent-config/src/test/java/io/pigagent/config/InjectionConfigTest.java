package io.pigagent.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Config coverage for {@code memory.injection} (capability {@code memory-retrieval-injection}):
 * default-safe (disabled + today's whole-file injection), YAML deserialization, missing-block
 * defaults, and null-tolerant setters.
 */
class InjectionConfigTest {

    private final ObjectMapper yaml = new ObjectMapper(new YAMLFactory());

    @Test
    void defaults_areDisabledAndSafe() {
        PigAgentConfig.InjectionConfig inj = new PigAgentConfig().getMemory().getInjection();
        assertThat(inj.isEnabled()).isFalse();
        assertThat(inj.getTopK()).isEqualTo(6);
        assertThat(inj.getEmbedderModelId()).isEmpty();
        assertThat(inj.getPinned().getSource()).isEqualTo("heading");
        assertThat(inj.getPinned().getHeading()).isEqualTo("Pinned");
        assertThat(inj.getPinned().getMaxChars()).isEqualTo(800);
    }

    @Test
    void missingBlock_yieldsDefaults() throws Exception {
        PigAgentConfig cfg = yaml.readValue("memory:\n  flush: always\n", PigAgentConfig.class);
        assertThat(cfg.getMemory().getInjection().isEnabled()).isFalse();
        assertThat(cfg.getMemory().getInjection().getTopK()).isEqualTo(6);
    }

    @Test
    void parsesConfiguredValues() throws Exception {
        String src = """
                memory:
                  injection:
                    enabled: true
                    top-k: 10
                    embedder-model-id: doubao-embedding
                    pinned:
                      source: head
                      heading: Identity
                      max-chars: 1200
                """;
        PigAgentConfig.InjectionConfig inj = yaml.readValue(src, PigAgentConfig.class).getMemory().getInjection();
        assertThat(inj.isEnabled()).isTrue();
        assertThat(inj.getTopK()).isEqualTo(10);
        assertThat(inj.getEmbedderModelId()).isEqualTo("doubao-embedding");
        assertThat(inj.getPinned().getSource()).isEqualTo("head");
        assertThat(inj.getPinned().getHeading()).isEqualTo("Identity");
        assertThat(inj.getPinned().getMaxChars()).isEqualTo(1200);
    }

    @Test
    void nullSettersTolerated() {
        PigAgentConfig.MemoryConfig m = new PigAgentConfig.MemoryConfig();
        m.setInjection(null);
        assertThat(m.getInjection()).isNotNull();

        PigAgentConfig.InjectionConfig inj = new PigAgentConfig.InjectionConfig();
        inj.setPinned(null);
        assertThat(inj.getPinned()).isNotNull();
        inj.setEmbedderModelId(null);
        assertThat(inj.getEmbedderModelId()).isEmpty();

        PigAgentConfig.PinnedConfig p = new PigAgentConfig.PinnedConfig();
        p.setSource(null);
        assertThat(p.getSource()).isEqualTo("heading");
        p.setHeading("  ");
        assertThat(p.getHeading()).isEqualTo("Pinned");
    }
}
