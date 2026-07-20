package io.pigagent.core.search;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * BM25 over the generic {@link SearchDocument} contract: relevant docs outrank irrelevant ones (latin +
 * CJK), empty index / no-match cases, and indexing an arbitrary (non-memory) {@code SearchDocument}
 * implementation. Uses a plain {@link TestDoc} fixture (not {@code MemoryDocument}) to prove the scorer
 * is generic — the assertions and expected rankings are identical to the pre-refactor test.
 */
class Bm25IndexTest {

    /** A minimal generic {@link SearchDocument} — proves the index needs only {@code id()} + {@code text()}. */
    private record TestDoc(String id, String text) implements SearchDocument {
    }

    @Test
    void ranksRelevantDocAboveIrrelevant() {
        Bm25Index idx = new Bm25Index();
        idx.index(List.of(
                new TestDoc("d1", "the user prefers dark mode themes"),
                new TestDoc("d2", "the weather is sunny today"),
                new TestDoc("d3", "a recipe for cooking pasta")));

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
                new TestDoc("d1", "用户的名字是罗湘赣"),
                new TestDoc("d2", "今天天气很好适合出门")));

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
        idx.index(List.of(new TestDoc("d1", "alpha beta gamma")));
        assertThat(idx.score("zzz qqq nonexistent")).isEmpty();
    }

    @Test
    void indexesAnyCustomSearchDocumentImplementation() {
        // An anonymous SearchDocument (not a record, not MemoryDocument) still indexes and scores by id.
        SearchDocument custom = new SearchDocument() {
            @Override
            public String id() {
                return "custom-1";
            }

            @Override
            public String text() {
                return "quarterly revenue projection spreadsheet";
            }
        };
        Bm25Index idx = new Bm25Index();
        idx.index(List.of(custom));

        assertThat(idx.score("revenue projection")).containsKey("custom-1");
    }
}
