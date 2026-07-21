package io.pigagent.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tri-state {@code memory.search.hybrid-enabled} (capability {@code hybrid-memory-search}, M-B):
 * <b>unset (default) = auto</b> → hybrid derived from whether an embedder is present; explicit
 * {@code true}/{@code false} overrides. Verifies the pure {@code resolveHybrid} derivation and that
 * an existing explicit boolean value is read back verbatim (backward compatible).
 */
class SearchConfigTriStateTest {

    private final ObjectMapper yaml = new ObjectMapper(new YAMLFactory());

    @Test
    void defaultHybridEnabledIsAutoNull() {
        // Arrange
        PigAgentConfig.SearchConfig s = new PigAgentConfig.SearchConfig();

        // Assert — unset means auto (no explicit override)
        assertThat(s.getHybridEnabled()).isNull();
        assertThat(s.hasExplicitHybrid()).isFalse();
    }

    @Test
    void resolveHybridAutoFollowsEmbedderPresence() {
        // Arrange — auto (unset)
        PigAgentConfig.SearchConfig s = new PigAgentConfig.SearchConfig();

        // Act + Assert — derive from embedder presence
        assertThat(s.resolveHybrid(true)).isTrue();   // embedder configured → hybrid on
        assertThat(s.resolveHybrid(false)).isFalse();  // no embedder → BM25-only (zero regression)
    }

    @Test
    void explicitTrueForcesHybridEvenWithoutEmbedder() {
        // Arrange
        PigAgentConfig.SearchConfig s = new PigAgentConfig.SearchConfig();
        s.setHybridEnabled(Boolean.TRUE);

        // Assert — explicit on overrides the derive (BM25-only degradation is handled by the index)
        assertThat(s.hasExplicitHybrid()).isTrue();
        assertThat(s.resolveHybrid(false)).isTrue();
    }

    @Test
    void explicitFalseForcesOffEvenWithEmbedder() {
        // Arrange — escape hatch: restore native keyword search + native memory tools
        PigAgentConfig.SearchConfig s = new PigAgentConfig.SearchConfig();
        s.setHybridEnabled(Boolean.FALSE);

        // Assert — explicit off overrides the derive
        assertThat(s.hasExplicitHybrid()).isTrue();
        assertThat(s.resolveHybrid(true)).isFalse();
    }

    @Test
    void existingExplicitBooleanReadBackVerbatim() throws Exception {
        // Arrange — a legacy config that wrote hybrid-enabled explicitly
        String on = "memory:\n  search:\n    hybrid-enabled: true\n";
        String off = "memory:\n  search:\n    hybrid-enabled: false\n";

        // Act
        PigAgentConfig.SearchConfig sOn = yaml.readValue(on, PigAgentConfig.class).getMemory().getSearch();
        PigAgentConfig.SearchConfig sOff = yaml.readValue(off, PigAgentConfig.class).getMemory().getSearch();

        // Assert — verbatim (backward compatible), not auto
        assertThat(sOn.getHybridEnabled()).isTrue();
        assertThat(sOff.getHybridEnabled()).isFalse();
    }

    @Test
    void missingSearchBlockIsAutoNull() throws Exception {
        // Act — no memory.search block at all
        PigAgentConfig.SearchConfig s =
                yaml.readValue("memory:\n  flush: always\n", PigAgentConfig.class).getMemory().getSearch();

        // Assert — auto (derive), zero regression when no embedder
        assertThat(s.getHybridEnabled()).isNull();
        assertThat(s.resolveHybrid(false)).isFalse();
    }
}
