package io.pigagent.core.memory.quality;

import io.pigagent.core.memory.quality.eval.MemoryEvalFixture;
import io.pigagent.core.memory.quality.eval.MemoryQualityAssertions;
import io.pigagent.core.memory.search.DeterministicEmbedder;
import io.pigagent.core.memory.search.Embedder;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Proves the memory-quality <b>eval harness structure</b> works offline with a deterministic driver:
 * a fixed-conversation {@link MemoryEvalFixture} + the {@link MemoryQualityAssertions} framework, driven
 * by a fake extraction (key facts + a near-duplicate) and the real {@link SemanticDeduplicator}. This
 * validates the harness/assertion framework itself — the real-model quality baseline is a deferred live
 * {@code *IT}. Generic enough for a future layered-decay capability (M-C) to reuse.
 */
class MemoryQualityHarnessTest {

    private final Embedder embedder = new DeterministicEmbedder();

    /** Fake extraction: emit the fixture's key facts as bullets, injecting a near-duplicate of the first. */
    private static String fakeConsolidatedMemory(MemoryEvalFixture fixture) {
        StringBuilder md = new StringBuilder("## Pinned\n");
        List<String> facts = fixture.expectedKeyFacts();
        md.append("- ").append(facts.get(0)).append('\n');
        md.append("- ").append(facts.get(0)).append("。\n"); // near-duplicate (same tokens, extra punctuation)
        for (int i = 1; i < facts.size(); i++) {
            md.append("- ").append(facts.get(i)).append('\n');
        }
        return md.toString();
    }

    @Test
    void harnessDetectsDuplicatesThenAssertsCleanDedupWithoutFactLoss() {
        MemoryEvalFixture fixture = MemoryEvalFixture.personalFactsWithDuplicate();
        String before = fakeConsolidatedMemory(fixture);

        // Framework works: key facts are present, and it flags the injected near-duplicate.
        MemoryQualityAssertions.assertKeyFactsRemembered(before, fixture);
        assertThatThrownBy(() ->
                MemoryQualityAssertions.assertNoNearDuplicates(before, fixture.dedupThreshold(), embedder))
                .isInstanceOf(AssertionError.class);

        // Deterministic driver: dedup the fact units.
        List<String> facts = FactUnitSplitter.factTexts(FactUnitSplitter.split(before));
        List<String> kept = new SemanticDeduplicator(embedder, fixture.dedupThreshold()).dedup(facts);
        String after = "## Pinned\n" + kept.stream().collect(Collectors.joining("\n")) + "\n";

        // After dedup: clean, no key fact lost, all facts still remembered.
        MemoryQualityAssertions.assertNoNearDuplicates(after, fixture.dedupThreshold(), embedder);
        MemoryQualityAssertions.assertNoKeyFactLost(before, after, fixture);
        MemoryQualityAssertions.assertKeyFactsRemembered(after, fixture);
        assertThat(kept).hasSize(facts.size() - 1); // exactly the one near-duplicate removed
    }

    @Test
    void assertKeyFactsRemembered_failsWhenAFactIsMissing() {
        MemoryEvalFixture fixture = MemoryEvalFixture.personalFactsWithDuplicate();
        assertThatThrownBy(() ->
                MemoryQualityAssertions.assertKeyFactsRemembered("## Pinned\n- 完全无关的内容", fixture))
                .isInstanceOf(AssertionError.class);
    }
}
