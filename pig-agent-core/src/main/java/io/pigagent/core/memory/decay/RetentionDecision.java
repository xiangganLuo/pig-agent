package io.pigagent.core.memory.decay;

/**
 * The per-fact retention verdict of the deterministic {@link RetentionScorer} — capability
 * {@code memory-layering-and-decay} (M-C). A pure state-machine output over
 * {@code (layer, recencyDays, accessCount)}: nothing here mutates memory; the curator interprets it.
 *
 * <ul>
 *   <li>{@link #KEEP} — retain the fact in its current layer.</li>
 *   <li>{@link #PROMOTE} — move the fact up one layer (reused enough to matter more).</li>
 *   <li>{@link #DEGRADE} — move the fact down one layer (stale, un-reused — a first step before archival).</li>
 *   <li>{@link #ARCHIVE} — move the fact out of {@code MEMORY.md} into the audit archive (stale volatile).</li>
 * </ul>
 */
public enum RetentionDecision {
    KEEP,
    PROMOTE,
    DEGRADE,
    ARCHIVE
}
