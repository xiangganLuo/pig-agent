package io.pigagent.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Hybrid memory search config block ({@code memory.search}, capability {@code hybrid-memory-search}):
 * default-safe (disabled + 0.7/0.3), YAML deserialization, missing-block defaults, null tolerance.
 */
class MemorySearchConfigYamlTest {

    private final ObjectMapper yaml = new ObjectMapper(new YAMLFactory());

    @Test
    void defaultsAreAutoAndBm25Heavy() {
        PigAgentConfig.SearchConfig s = new PigAgentConfig().getMemory().getSearch();
        // M-B: default is auto (unset) → derive; no embedder → BM25-only (today's keyword search)
        assertThat(s.getHybridEnabled()).isNull();
        assertThat(s.resolveHybrid(false)).isFalse();
        assertThat(s.getBm25Weight()).isEqualTo(0.7);
        assertThat(s.getVectorWeight()).isEqualTo(0.3);
        assertThat(s.getEmbedderModelId()).isEmpty();
        assertThat(s.getCandidateMultiplier()).isEqualTo(4);
        assertThat(s.getMinScore()).isEqualTo(0.0);
        assertThat(s.getTopK()).isEqualTo(8);
        assertThat(s.getRebuildThrottleSeconds()).isEqualTo(5);
    }

    @Test
    void missingSearchBlockYieldsDefaults() throws Exception {
        PigAgentConfig cfg = yaml.readValue("memory:\n  flush: always\n", PigAgentConfig.class);
        assertThat(cfg.getMemory().getSearch().getHybridEnabled()).isNull(); // auto (derive)
        assertThat(cfg.getMemory().getSearch().getBm25Weight()).isEqualTo(0.7);
    }

    @Test
    void parsesConfiguredValues() throws Exception {
        String src = """
                memory:
                  search:
                    hybrid-enabled: true
                    bm25-weight: 0.6
                    vector-weight: 0.4
                    embedder-model-id: doubao-embedding
                    candidate-multiplier: 6
                    min-score: 0.1
                    top-k: 12
                    rebuild-throttle-seconds: 10
                """;
        PigAgentConfig.SearchConfig s = yaml.readValue(src, PigAgentConfig.class).getMemory().getSearch();
        assertThat(s.getHybridEnabled()).isTrue(); // explicit true read back verbatim
        assertThat(s.getBm25Weight()).isEqualTo(0.6);
        assertThat(s.getVectorWeight()).isEqualTo(0.4);
        assertThat(s.getEmbedderModelId()).isEqualTo("doubao-embedding");
        assertThat(s.getCandidateMultiplier()).isEqualTo(6);
        assertThat(s.getMinScore()).isEqualTo(0.1);
        assertThat(s.getTopK()).isEqualTo(12);
        assertThat(s.getRebuildThrottleSeconds()).isEqualTo(10);
    }

    @Test
    void nullSettersTolerated() {
        PigAgentConfig.MemoryConfig m = new PigAgentConfig.MemoryConfig();
        m.setSearch(null);
        assertThat(m.getSearch()).isNotNull();

        PigAgentConfig.SearchConfig s = new PigAgentConfig.SearchConfig();
        s.setEmbedderModelId(null);
        assertThat(s.getEmbedderModelId()).isEmpty();
    }
}
