package io.pigagent.core.memory.search;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** Core {@link MemorySearchConfig} value object: defaults and safe clamping. */
class MemorySearchConfigTest {

    @Test
    void defaultsAreConservativeBm25Heavy() {
        MemorySearchConfig c = MemorySearchConfig.defaults();
        assertThat(c.bm25Weight()).isEqualTo(0.7);
        assertThat(c.vectorWeight()).isEqualTo(0.3);
        assertThat(c.candidateMultiplier()).isEqualTo(4);
        assertThat(c.minScore()).isEqualTo(0.0);
        assertThat(c.topK()).isEqualTo(8);
        assertThat(c.rebuildThrottleMillis()).isEqualTo(5000L);
    }

    @Test
    void clampsInvalidValues() {
        MemorySearchConfig c = new MemorySearchConfig(-1.0, -2.0, 0, 5.0, 0, -100L);
        assertThat(c.bm25Weight()).isZero();
        assertThat(c.vectorWeight()).isZero();
        assertThat(c.candidateMultiplier()).isEqualTo(1);
        assertThat(c.minScore()).isEqualTo(1.0); // clamped to [0,1]
        assertThat(c.topK()).isEqualTo(1);
        assertThat(c.rebuildThrottleMillis()).isZero();
    }
}
