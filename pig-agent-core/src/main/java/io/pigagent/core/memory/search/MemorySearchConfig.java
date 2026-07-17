package io.pigagent.core.memory.search;

/**
 * Immutable settings for the hybrid memory search index — capability {@code hybrid-memory-search}
 * (mirrors {@code SandboxPolicy}: a config-backed value object the wiring layer builds from
 * {@code memory.search}). All inputs are clamped to safe ranges in the compact constructor.
 *
 * @param bm25Weight            weight on the normalized BM25 component (default 0.7; clamped ≥ 0)
 * @param vectorWeight          weight on the normalized vector component (default 0.3; clamped ≥ 0)
 * @param candidateMultiplier   fetch {@code topK · multiplier} vector candidates before blending (≥ 1)
 * @param minScore              drop blended scores below this (clamped to {@code [0, 1]})
 * @param topK                  max hits returned (≥ 1)
 * @param rebuildThrottleMillis min millis between incremental rebuilds (≥ 0)
 */
public record MemorySearchConfig(double bm25Weight, double vectorWeight, int candidateMultiplier,
                                 double minScore, int topK, long rebuildThrottleMillis) {

    public MemorySearchConfig {
        bm25Weight = Math.max(0.0, bm25Weight);
        vectorWeight = Math.max(0.0, vectorWeight);
        candidateMultiplier = Math.max(1, candidateMultiplier);
        minScore = Math.min(1.0, Math.max(0.0, minScore));
        topK = Math.max(1, topK);
        rebuildThrottleMillis = Math.max(0L, rebuildThrottleMillis);
    }

    /** Conservative defaults: BM25-heavy blend (0.7/0.3), ×4 candidates, keep-all, top-8, 5s throttle. */
    public static MemorySearchConfig defaults() {
        return new MemorySearchConfig(0.7, 0.3, 4, 0.0, 8, 5000L);
    }
}
