package io.pigagent.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Config coverage for {@code memory.consolidation-quality} (capability
 * {@code memory-consolidation-quality}): default-safe (empty prompts + dedup off = today's behavior),
 * YAML deserialization, missing-block defaults, clamp, and null-tolerant setters.
 */
class ConsolidationQualityConfigTest {

    private final ObjectMapper yaml = new ObjectMapper(new YAMLFactory());

    @Test
    void defaults_areEmptyPromptsAndDedupOff() {
        PigAgentConfig.ConsolidationQualityConfig cq =
                new PigAgentConfig().getMemory().getConsolidationQuality();
        assertThat(cq.getFlushPrompt()).isEmpty();
        assertThat(cq.getConsolidationPrompt()).isEmpty();
        assertThat(cq.getDedup().isEnabled()).isFalse();
        assertThat(cq.getDedup().getSimilarityThreshold()).isEqualTo(0.9);
        assertThat(cq.getDedup().getEmbedderModelId()).isEmpty();
        assertThat(cq.getDedup().getMinGapMinutes()).isEqualTo(60);
    }

    @Test
    void missingBlock_yieldsDefaults() throws Exception {
        PigAgentConfig cfg = yaml.readValue("memory:\n  flush: always\n", PigAgentConfig.class);
        PigAgentConfig.ConsolidationQualityConfig cq = cfg.getMemory().getConsolidationQuality();
        assertThat(cq.getConsolidationPrompt()).isEmpty();
        assertThat(cq.getDedup().isEnabled()).isFalse();
    }

    @Test
    void parsesConfiguredValues() throws Exception {
        String src = """
                memory:
                  consolidation-quality:
                    flush-prompt: "Extract durable facts as bullets."
                    consolidation-prompt: "Merge within %d tokens (~%d chars)."
                    dedup:
                      enabled: true
                      similarity-threshold: 0.85
                      embedder-model-id: doubao-embedding
                      min-gap-minutes: 120
                """;
        PigAgentConfig.ConsolidationQualityConfig cq =
                yaml.readValue(src, PigAgentConfig.class).getMemory().getConsolidationQuality();
        assertThat(cq.getFlushPrompt()).contains("durable facts");
        assertThat(cq.getConsolidationPrompt()).contains("%d");
        assertThat(cq.getDedup().isEnabled()).isTrue();
        assertThat(cq.getDedup().getSimilarityThreshold()).isEqualTo(0.85);
        assertThat(cq.getDedup().getEmbedderModelId()).isEqualTo("doubao-embedding");
        assertThat(cq.getDedup().getMinGapMinutes()).isEqualTo(120);
    }

    @Test
    void similarityThresholdClampedToUnitRange() {
        PigAgentConfig.DedupConfig d = new PigAgentConfig.DedupConfig();
        d.setSimilarityThreshold(5.0);
        assertThat(d.getSimilarityThreshold()).isEqualTo(1.0);
        d.setSimilarityThreshold(-1.0);
        assertThat(d.getSimilarityThreshold()).isEqualTo(0.0);
    }

    @Test
    void minGapMinutesClampedToAtLeastOne() {
        PigAgentConfig.DedupConfig d = new PigAgentConfig.DedupConfig();
        d.setMinGapMinutes(0);
        assertThat(d.getMinGapMinutes()).isEqualTo(1);
    }

    @Test
    void nullSettersTolerated() {
        PigAgentConfig.MemoryConfig m = new PigAgentConfig.MemoryConfig();
        m.setConsolidationQuality(null);
        assertThat(m.getConsolidationQuality()).isNotNull();

        PigAgentConfig.ConsolidationQualityConfig cq = new PigAgentConfig.ConsolidationQualityConfig();
        cq.setFlushPrompt(null);
        assertThat(cq.getFlushPrompt()).isEmpty();
        cq.setConsolidationPrompt(null);
        assertThat(cq.getConsolidationPrompt()).isEmpty();
        cq.setDedup(null);
        assertThat(cq.getDedup()).isNotNull();

        PigAgentConfig.DedupConfig d = new PigAgentConfig.DedupConfig();
        d.setEmbedderModelId(null);
        assertThat(d.getEmbedderModelId()).isEmpty();
    }
}
