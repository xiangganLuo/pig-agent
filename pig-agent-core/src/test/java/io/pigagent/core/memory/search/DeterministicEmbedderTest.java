package io.pigagent.core.memory.search;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/** Deterministic fake embedder: reproducible, correctly-dimensioned, L2-normalized, overlap-sensitive. */
class DeterministicEmbedderTest {

    @Test
    void sameTextProducesSameVector() {
        DeterministicEmbedder e = new DeterministicEmbedder();
        assertThat(e.embed("hello dark world")).isEqualTo(e.embed("hello dark world"));
    }

    @Test
    void honorsDimension() {
        assertThat(new DeterministicEmbedder(32).embed("something")).hasSize(32);
    }

    @Test
    void vectorIsL2Normalized() {
        float[] v = new DeterministicEmbedder().embed("some tokens go here");
        double norm = 0.0;
        for (float f : v) {
            norm += (double) f * f;
        }
        assertThat(Math.sqrt(norm)).isCloseTo(1.0, within(1e-5));
    }

    @Test
    void sharedTokensGiveHigherCosineThanUnrelated() {
        DeterministicEmbedder e = new DeterministicEmbedder();
        float[] a = e.embed("dark mode theme preference");
        float[] b = e.embed("dark mode theme setting");   // shares 3 tokens with a
        float[] c = e.embed("banana pancake breakfast");  // shares none
        assertThat(Vectors.cosine(a, b)).isGreaterThan(Vectors.cosine(a, c));
    }

    @Test
    void emptyTextGivesZeroVector() {
        float[] v = new DeterministicEmbedder().embed("");
        assertThat(Vectors.cosine(v, new DeterministicEmbedder().embed("x"))).isZero();
    }
}
