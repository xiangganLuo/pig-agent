package io.pigagent.core.memory.search;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** Hybrid ranker: normalized 0.7/0.3 blend, weight flip, dedup, top-K, min-score, BM25-only degradation. */
class HybridRankerTest {

    @Test
    void blendsBm25AndVectorScores() {
        Map<String, Double> bm25 = Map.of("a", 10.0, "b", 1.0);   // norm: a=1, b=0
        Map<String, Double> vector = Map.of("a", 0.0, "b", 1.0);  // norm: a=0, b=1

        var ranked = HybridRanker.rank(bm25, vector, 0.7, 0.3, 0.0, 10);

        // a = 0.7*1 + 0.3*0 = 0.7 ; b = 0.7*0 + 0.3*1 = 0.3
        assertThat(ranked).extracting(HybridRanker.Scored::id).containsExactly("a", "b");
        assertThat(ranked.get(0).score()).isEqualTo(0.7);
    }

    @Test
    void vectorWeightCanFlipTheOrder() {
        Map<String, Double> bm25 = Map.of("a", 1.0, "b", 0.8);    // norm: a=1, b=0
        Map<String, Double> vector = Map.of("a", 0.0, "b", 1.0);  // norm: a=0, b=1

        var ranked = HybridRanker.rank(bm25, vector, 0.3, 0.7, 0.0, 10);

        assertThat(ranked.get(0).id()).isEqualTo("b"); // vector-heavy flips the order
    }

    @Test
    void deduplicatesAcrossSources() {
        var ranked = HybridRanker.rank(Map.of("a", 1.0), Map.of("a", 1.0), 0.7, 0.3, 0.0, 10);
        assertThat(ranked).extracting(HybridRanker.Scored::id).containsExactly("a"); // once, not twice
    }

    @Test
    void keepsAtMostTopK() {
        Map<String, Double> bm25 = Map.of("a", 3.0, "b", 2.0, "c", 1.0);
        var ranked = HybridRanker.rank(bm25, Map.of(), 1.0, 0.0, 0.0, 2);
        assertThat(ranked).extracting(HybridRanker.Scored::id).containsExactly("a", "b");
    }

    @Test
    void bm25OnlyWhenVectorMapEmpty() {
        Map<String, Double> bm25 = Map.of("a", 3.0, "b", 1.0);
        var ranked = HybridRanker.rank(bm25, Map.of(), 0.7, 0.3, 0.0, 10);
        assertThat(ranked.get(0).id()).isEqualTo("a"); // order preserved by min-max normalization
    }

    @Test
    void minScoreFiltersLowBlends() {
        Map<String, Double> bm25 = Map.of("a", 10.0, "b", 1.0); // norm: a=1 → 0.7, b=0 → 0.0
        var ranked = HybridRanker.rank(bm25, Map.of(), 0.7, 0.3, 0.5, 10);
        assertThat(ranked).extracting(HybridRanker.Scored::id).containsExactly("a"); // b dropped (< 0.5)
    }

    @Test
    void normalizeMapsAllEqualToOne() {
        assertThat(HybridRanker.normalize(Map.of("a", 5.0, "b", 5.0)))
                .containsEntry("a", 1.0).containsEntry("b", 1.0);
    }

    @Test
    void emptyInputsYieldEmptyRanking() {
        assertThat(HybridRanker.rank(Map.of(), Map.of(), 0.7, 0.3, 0.0, 10)).isEmpty();
    }
}
