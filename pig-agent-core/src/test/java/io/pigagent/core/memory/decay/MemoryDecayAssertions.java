package io.pigagent.core.memory.decay;

import io.pigagent.core.memory.quality.MemoryLayerFormat;
import io.pigagent.core.memory.quality.MemoryLayerFormat.Layer;
import io.pigagent.core.search.Tokenizer;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The decay-dimension assertion framework for memory-quality evaluation — capability
 * {@code memory-layering-and-decay} (M-C, task 6.2). Complements M-D's {@code MemoryQualityAssertions}
 * (key facts remembered / no near-duplicates / no key fact lost) with the three metabolism checks:
 * <b>stale facts degraded/archived</b>, <b>Pinned never archived</b>, and <b>reused facts retained</b>.
 * Each is deterministic + offline (token-subset matching via the shared {@link Tokenizer}; layers read
 * via the M-D {@link MemoryLayerFormat} contract), so the harness structure is provable without a live
 * model. The real-model aging quality baseline is a deferred live {@code *IT}.
 */
public final class MemoryDecayAssertions {

    private MemoryDecayAssertions() {
    }

    /** The fact's content tokens appear under {@code layer}'s section of the curated {@code MEMORY.md}. */
    public static void assertFactInLayer(String memoryMd, String fact, Layer layer) {
        Map<Layer, String> layers = MemoryLayerFormat.parse(memoryMd);
        String section = layers.getOrDefault(layer, "");
        assertThat(containsAllTokens(new HashSet<>(Tokenizer.tokenize(section)), fact))
                .as("fact '%s' is under the %s layer", fact, layer.heading())
                .isTrue();
    }

    /** A stale fact was archived: present in the archive ledger and absent from {@code MEMORY.md}. */
    public static void assertStaleFactArchived(String archiveContent, String memoryMd, String staleFact) {
        assertThat(containsAllTokens(new HashSet<>(Tokenizer.tokenize(archiveContent)), staleFact))
                .as("stale fact '%s' was archived", staleFact)
                .isTrue();
        assertThat(containsAllTokens(new HashSet<>(Tokenizer.tokenize(memoryMd)), staleFact))
                .as("stale fact '%s' was removed from MEMORY.md", staleFact)
                .isFalse();
    }

    /** A reused fact was retained in {@code MEMORY.md} (reinforcement protected it from decay). */
    public static void assertReusedFactRetained(String memoryMd, String reusedFact) {
        assertThat(containsAllTokens(new HashSet<>(Tokenizer.tokenize(memoryMd)), reusedFact))
                .as("reused fact '%s' retained in MEMORY.md", reusedFact)
                .isTrue();
    }

    /** No Pinned (identity/core) fact was archived — Pinned is permanent. */
    public static void assertPinnedNeverArchived(String archiveContent, List<String> pinnedFacts) {
        Set<String> archiveTokens = new HashSet<>(Tokenizer.tokenize(archiveContent));
        for (String pinned : pinnedFacts) {
            assertThat(containsAllTokens(archiveTokens, pinned))
                    .as("pinned fact '%s' MUST NOT be archived", pinned)
                    .isFalse();
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
