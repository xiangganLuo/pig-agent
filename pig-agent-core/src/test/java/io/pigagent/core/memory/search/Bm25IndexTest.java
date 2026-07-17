package io.pigagent.core.memory.search;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** BM25: relevant docs outrank irrelevant ones (latin + CJK), empty index / no-match cases. */
class Bm25IndexTest {

    @Test
    void ranksRelevantDocAboveIrrelevant() {
        Bm25Index idx = new Bm25Index();
        idx.index(List.of(
                new MemoryDocument("d1", "x", "the user prefers dark mode themes"),
                new MemoryDocument("d2", "x", "the weather is sunny today"),
                new MemoryDocument("d3", "x", "a recipe for cooking pasta")));

        Map<String, Double> scores = idx.score("dark mode preference");

        assertThat(scores).containsKey("d1");
        assertThat(scores.getOrDefault("d1", 0.0))
                .isGreaterThan(scores.getOrDefault("d2", 0.0))
                .isGreaterThan(scores.getOrDefault("d3", 0.0));
    }

    @Test
    void chineseQueryRanksTheDocWithTheName() {
        Bm25Index idx = new Bm25Index();
        idx.index(List.of(
                new MemoryDocument("d1", "x", "用户的名字是罗湘赣"),
                new MemoryDocument("d2", "x", "今天天气很好适合出门")));

        Map<String, Double> scores = idx.score("罗湘赣");

        assertThat(scores).containsKey("d1");
        assertThat(scores.getOrDefault("d1", 0.0)).isGreaterThan(scores.getOrDefault("d2", 0.0));
    }

    @Test
    void emptyIndexScoresNothing() {
        assertThat(new Bm25Index().score("anything")).isEmpty();
    }

    @Test
    void queryWithNoMatchingTermsScoresNothing() {
        Bm25Index idx = new Bm25Index();
        idx.index(List.of(new MemoryDocument("d1", "x", "alpha beta gamma")));
        assertThat(idx.score("zzz qqq nonexistent")).isEmpty();
    }
}
