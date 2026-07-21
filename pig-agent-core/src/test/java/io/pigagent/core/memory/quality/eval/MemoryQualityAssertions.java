package io.pigagent.core.memory.quality.eval;

import io.pigagent.core.memory.quality.FactUnitSplitter;
import io.pigagent.core.memory.quality.SemanticDeduplicator;
import io.pigagent.core.memory.search.Embedder;
import io.pigagent.core.search.Tokenizer;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The reusable assertion framework for memory-quality evaluation — capability
 * {@code memory-consolidation-quality}. Three checks matching the M-D quality goals: <b>key facts
 * remembered</b>, <b>no near-duplicates</b>, and <b>no key fact lost</b> (dedup didn't over-prune). Each
 * is deterministic and offline (token-subset matching via the shared {@link Tokenizer}; near-duplicate
 * detection reuses {@link SemanticDeduplicator}), so the harness structure is provable without a live
 * model. The real-model quality baseline is a deferred live {@code *IT}.
 */
public final class MemoryQualityAssertions {

    private MemoryQualityAssertions() {
    }

    /** Every expected key fact's content tokens are present in {@code memoryMd}. */
    public static void assertKeyFactsRemembered(String memoryMd, MemoryEvalFixture fixture) {
        Set<String> memoryTokens = new HashSet<>(Tokenizer.tokenize(memoryMd));
        for (String fact : fixture.expectedKeyFacts()) {
            assertThat(containsAllTokens(memoryTokens, fact))
                    .as("key fact remembered: '%s' in MEMORY.md", fact)
                    .isTrue();
        }
    }

    /**
     * No two fact units in {@code memoryMd} are near-duplicates at {@code threshold}: running the
     * deduplicator leaves the fact-unit count unchanged.
     */
    public static void assertNoNearDuplicates(String memoryMd, double threshold, Embedder embedder) {
        List<String> facts = FactUnitSplitter.factTexts(FactUnitSplitter.split(memoryMd));
        int survivors = new SemanticDeduplicator(embedder, threshold).survivingIndices(facts).size();
        assertThat(survivors)
                .as("no near-duplicate fact units remain in MEMORY.md (@%.2f)", threshold)
                .isEqualTo(facts.size());
    }

    /** Every key fact present in {@code before} is still present in {@code after} (dedup didn't drop it). */
    public static void assertNoKeyFactLost(String before, String after, MemoryEvalFixture fixture) {
        Set<String> beforeTokens = new HashSet<>(Tokenizer.tokenize(before));
        Set<String> afterTokens = new HashSet<>(Tokenizer.tokenize(after));
        for (String fact : fixture.expectedKeyFacts()) {
            if (containsAllTokens(beforeTokens, fact)) {
                assertThat(containsAllTokens(afterTokens, fact))
                        .as("key fact not lost by dedup: '%s'", fact)
                        .isTrue();
            }
        }
    }

    private static boolean containsAllTokens(Set<String> haystackTokens, String needle) {
        List<String> needleTokens = Tokenizer.tokenize(needle);
        if (needleTokens.isEmpty()) {
            return true;
        }
        return haystackTokens.containsAll(needleTokens);
    }
}
