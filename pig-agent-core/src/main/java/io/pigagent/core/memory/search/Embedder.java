package io.pigagent.core.memory.search;

/**
 * The (mockable) embedding-model seam for the vector half of hybrid memory search — capability
 * {@code hybrid-memory-search}. Given a piece of text, produce a dense vector; cosine similarity of
 * two such vectors is the semantic signal blended with BM25.
 *
 * <p><b>Spike outcome (OD7).</b> AgentScope 2.0 exposes no embedding Model API and no offline embedding
 * model is available, so a real embedder ({@link OpenAiCompatibleEmbedder}) is a live network call
 * whose quality is verified only under {@code /ls:itest}. Offline tests inject a deterministic fake
 * ({@link DeterministicEmbedder}), so the hybrid blend/rank/index logic is fully exercised without any
 * live model. A {@code null} embedder anywhere means "no vector layer" → the index degrades to
 * BM25-only (never an error).
 */
@FunctionalInterface
public interface Embedder {

    /**
     * Embed {@code text} into a dense vector. Implementations SHOULD return an L2-normalized vector (so
     * cosine reduces to a dot product) but callers do not rely on it. May throw on a transient failure;
     * callers treat a failure as "no vector for this item" (graceful BM25-only degradation).
     */
    float[] embed(String text);
}
