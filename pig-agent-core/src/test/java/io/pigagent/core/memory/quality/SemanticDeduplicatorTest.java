package io.pigagent.core.memory.quality;

import io.pigagent.core.memory.search.DeterministicEmbedder;
import io.pigagent.core.memory.search.Embedder;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Deterministic offline proof of the semantic deduplicator over both similarity channels — cosine
 * (with the offline {@link DeterministicEmbedder}) and BM25 (no embedder). Exercises exact duplicates,
 * a reordered-token near-duplicate (semantic &gt; pure string match), clearly-distinct facts kept,
 * longest-representative selection, and degenerate inputs.
 */
class SemanticDeduplicatorTest {

    private final Embedder embedder = new DeterministicEmbedder();

    @Test
    void exactDuplicateDeduped_cosineChannel() {
        SemanticDeduplicator dedup = new SemanticDeduplicator(embedder, 0.9);
        List<String> out = dedup.dedup(List.of("alpha beta gamma", "alpha beta gamma", "delta epsilon zeta"));
        assertThat(out).containsExactly("alpha beta gamma", "delta epsilon zeta");
    }

    @Test
    void reorderedTokensAreNearDuplicate_notPureStringMatch() {
        // Different strings, identical token bag → identical deterministic vector → cosine 1.0 → merged.
        SemanticDeduplicator dedup = new SemanticDeduplicator(embedder, 0.9);
        List<String> out = dedup.dedup(List.of("alpha beta gamma", "gamma beta alpha"));
        assertThat(out).hasSize(1);
    }

    @Test
    void clearlyDifferentFactsBothKept() {
        SemanticDeduplicator dedup = new SemanticDeduplicator(embedder, 0.9);
        List<String> out = dedup.dedup(List.of("我在杭州工作", "我最喜欢的编程语言是 Java"));
        assertThat(out).hasSize(2);
    }

    @Test
    void representativeIsLongest_tieBrokenByLaterIndex() {
        // Same token bag (punctuation stripped) but different lengths → the longer/more-informative wins.
        SemanticDeduplicator dedup = new SemanticDeduplicator(embedder, 0.9);
        List<String> out = dedup.dedup(List.of("alpha beta gamma", "alpha, beta, gamma!"));
        assertThat(out).containsExactly("alpha, beta, gamma!");
    }

    @Test
    void emptyAndSingleReturnedAsIs() {
        SemanticDeduplicator dedup = new SemanticDeduplicator(embedder, 0.9);
        assertThat(dedup.dedup(List.of())).isEmpty();
        assertThat(dedup.dedup(List.of("only one"))).containsExactly("only one");
    }

    @Test
    void bm25Channel_exactDuplicateDeduped_noEmbedder() {
        SemanticDeduplicator dedup = new SemanticDeduplicator(null, 0.9);
        assertThat(dedup.vectorEnabled()).isFalse();
        List<String> out = dedup.dedup(List.of("hello world foo", "hello world foo", "totally different text"));
        assertThat(out).containsExactly("hello world foo", "totally different text");
    }

    @Test
    void bm25Channel_differentFactsKept_noEmbedder() {
        SemanticDeduplicator dedup = new SemanticDeduplicator(null, 0.9);
        List<String> out = dedup.dedup(List.of("hello world foo", "totally different text here"));
        assertThat(out).hasSize(2);
    }

    @Test
    void survivingIndicesAreAscending() {
        SemanticDeduplicator dedup = new SemanticDeduplicator(embedder, 0.9);
        // idx0 & idx2 are the same fact; the representative (longer/later) is idx2.
        List<Integer> survivors = dedup.survivingIndices(
                List.of("cat dog", "bird", "cat dog"));
        assertThat(survivors).containsExactly(1, 2);
    }

    @Test
    void thresholdIsClampedToUnitRange() {
        assertThat(new SemanticDeduplicator(embedder, 5.0).vectorEnabled()).isTrue();
        assertThat(new SemanticDeduplicator(embedder, -1.0).vectorEnabled()).isTrue();
        // A threshold clamped to 1.0 only merges perfect matches: near-but-not-identical stays split.
        SemanticDeduplicator strict = new SemanticDeduplicator(embedder, 5.0); // clamped to 1.0
        assertThat(strict.dedup(List.of("alpha beta gamma", "alpha beta delta"))).hasSize(2);
    }
}
