package io.pigagent.core.memory.decay;

import io.pigagent.core.memory.quality.MemoryLayerFormat.Layer;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link RetentionScorer} — the deterministic retention state machine (M-C, D3).
 * Thresholds: staleAfterDays=30, archiveAfterDays=90, reinforce=2, promote=5.
 */
class RetentionScorerTest {

    private final RetentionScorer scorer = new RetentionScorer(30, 90, 2, 5);

    @Test
    void pinnedIsAlwaysKeptRegardlessOfRecencyOrAccess() {
        assertThat(scorer.decide(Layer.PINNED, 9999, 0)).isEqualTo(RetentionDecision.KEEP);
        assertThat(scorer.decide(Layer.PINNED, 0, 0)).isEqualTo(RetentionDecision.KEEP);
    }

    @Test
    void staleUnreusedGeneralIsDegraded() {
        assertThat(scorer.decide(Layer.GENERAL, 31, 0)).isEqualTo(RetentionDecision.DEGRADE);
    }

    @Test
    void freshGeneralIsKept() {
        assertThat(scorer.decide(Layer.GENERAL, 10, 0)).isEqualTo(RetentionDecision.KEEP);
        assertThat(scorer.decide(Layer.GENERAL, 30, 0)).isEqualTo(RetentionDecision.KEEP); // boundary: not > 30
    }

    @Test
    void staleUnreusedVolatileIsArchived() {
        assertThat(scorer.decide(Layer.VOLATILE, 91, 0)).isEqualTo(RetentionDecision.ARCHIVE);
    }

    @Test
    void freshVolatileIsKept() {
        assertThat(scorer.decide(Layer.VOLATILE, 90, 0)).isEqualTo(RetentionDecision.KEEP); // boundary
        assertThat(scorer.decide(Layer.VOLATILE, 5, 0)).isEqualTo(RetentionDecision.KEEP);
    }

    @Test
    void reuseAtReinforceThresholdProtectsFromDecay() {
        // A stale GENERAL fact that would otherwise degrade is protected when reused enough.
        assertThat(scorer.decide(Layer.GENERAL, 999, 2)).isEqualTo(RetentionDecision.KEEP);
        assertThat(scorer.decide(Layer.VOLATILE, 999, 3)).isEqualTo(RetentionDecision.KEEP);
    }

    @Test
    void heavyReusePromotesNonPinnedLayer() {
        assertThat(scorer.decide(Layer.VOLATILE, 999, 5)).isEqualTo(RetentionDecision.PROMOTE);
        assertThat(scorer.decide(Layer.GENERAL, 1, 6)).isEqualTo(RetentionDecision.PROMOTE);
    }

    @Test
    void promotePrecedesPinnedGuardOnlyForNonPinned() {
        // PINNED never promotes (already the top layer) — stays KEEP even with heavy reuse.
        assertThat(scorer.decide(Layer.PINNED, 1, 99)).isEqualTo(RetentionDecision.KEEP);
    }

    @Test
    void nullLayerIsKept() {
        assertThat(scorer.decide(null, 999, 0)).isEqualTo(RetentionDecision.KEEP);
    }

    @Test
    void thresholdsAreClampedToSaneMinimums() {
        // stale/archive clamp so archive >= stale >= 1; reinforce/promote clamp so promote >= reinforce >= 1.
        RetentionScorer clamped = new RetentionScorer(-5, -10, 0, -3);
        // stale→1, archive→max(1,-10)=1; a GENERAL at recency 2 (>1) degrades.
        assertThat(clamped.decide(Layer.GENERAL, 2, 0)).isEqualTo(RetentionDecision.DEGRADE);
        // reinforce→1, promote→max(1,-3)=1; accessCount 1 hits promote.
        assertThat(clamped.decide(Layer.VOLATILE, 2, 1)).isEqualTo(RetentionDecision.PROMOTE);
    }

    @Test
    void negativeRecencyIsTreatedAsFresh() {
        assertThat(scorer.decide(Layer.GENERAL, -1, 0)).isEqualTo(RetentionDecision.KEEP);
    }
}
