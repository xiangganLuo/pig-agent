package io.pigagent.core.memory.decay;

import io.pigagent.core.memory.quality.MemoryLayerFormat.Layer;

/**
 * Deterministic pure-function retention state machine — capability {@code memory-layering-and-decay}
 * (M-C, D3). Maps {@code (layer, recencyDays, accessCount)} to a {@link RetentionDecision} with a fixed,
 * offline-provable policy:
 * <ol>
 *   <li>{@link Layer#PINNED} → {@link RetentionDecision#KEEP} always (identity/core is permanent — never
 *       auto-degraded or archived).</li>
 *   <li>Reuse strengthens: {@code accessCount >= promoteAccessThreshold} on a non-PINNED fact →
 *       {@link RetentionDecision#PROMOTE}; {@code accessCount >= reinforceAccessThreshold} → protected
 *       {@link RetentionDecision#KEEP} (decay skipped this pass).</li>
 *   <li>Otherwise decay by recency: {@link Layer#GENERAL} older than {@code staleAfterDays} →
 *       {@link RetentionDecision#DEGRADE} (to VOLATILE, a first "seen-again" chance); {@link Layer#VOLATILE}
 *       older than {@code archiveAfterDays} → {@link RetentionDecision#ARCHIVE}; else
 *       {@link RetentionDecision#KEEP}.</li>
 * </ol>
 *
 * <p>The two-step decay (GENERAL → VOLATILE → ARCHIVE) means a durable fact is never archived in one
 * pass — reducing accidental loss. All thresholds are clamped to sane minimums; pure, no I/O, no model.
 */
public final class RetentionScorer {

    private final int staleAfterDays;
    private final int archiveAfterDays;
    private final int reinforceAccessThreshold;
    private final int promoteAccessThreshold;

    /**
     * @param staleAfterDays           GENERAL facts older than this (and un-reused) degrade (min 1)
     * @param archiveAfterDays         VOLATILE facts older than this (and un-reused) archive (min ≥ stale)
     * @param reinforceAccessThreshold access count that protects a fact from decay this pass (min 1)
     * @param promoteAccessThreshold   access count that promotes a non-PINNED fact a layer (min ≥ reinforce)
     */
    public RetentionScorer(int staleAfterDays, int archiveAfterDays,
                           int reinforceAccessThreshold, int promoteAccessThreshold) {
        this.staleAfterDays = Math.max(1, staleAfterDays);
        this.archiveAfterDays = Math.max(this.staleAfterDays, archiveAfterDays);
        this.reinforceAccessThreshold = Math.max(1, reinforceAccessThreshold);
        this.promoteAccessThreshold = Math.max(this.reinforceAccessThreshold, promoteAccessThreshold);
    }

    /** The retention verdict for a fact currently in {@code layer} with the given recency + reuse signals. */
    public RetentionDecision decide(Layer layer, long recencyDays, int accessCount) {
        if (layer == null || layer == Layer.PINNED) {
            return RetentionDecision.KEEP; // identity/core is permanent
        }
        if (accessCount >= promoteAccessThreshold) {
            return RetentionDecision.PROMOTE; // strong reuse → promote a layer
        }
        if (accessCount >= reinforceAccessThreshold) {
            return RetentionDecision.KEEP; // reused → protected from decay this pass
        }
        long recency = Math.max(0, recencyDays);
        return switch (layer) {
            case GENERAL -> recency > staleAfterDays ? RetentionDecision.DEGRADE : RetentionDecision.KEEP;
            case VOLATILE -> recency > archiveAfterDays ? RetentionDecision.ARCHIVE : RetentionDecision.KEEP;
            default -> RetentionDecision.KEEP;
        };
    }
}
