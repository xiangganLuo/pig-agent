package io.pigagent.core.memory.search;

/**
 * A deterministic, dependency-free {@link Embedder} used for <b>offline tests</b> and as a safe
 * fallback — capability {@code hybrid-memory-search}. It hashes tokens (via {@link Tokenizer}) into a
 * fixed-dimension vector with term-frequency weighting and L2-normalizes, so texts sharing tokens get
 * similar vectors — enough to deterministically exercise the hybrid blend/rank without a live model.
 *
 * <p><b>Not a semantic model.</b> It has no learned semantics (synonyms/paraphrases do not map close);
 * it exists so the vector <em>plumbing</em> is testable offline. Production semantic quality needs a
 * real embedder ({@link OpenAiCompatibleEmbedder}), verified under {@code /ls:itest}.
 */
public final class DeterministicEmbedder implements Embedder {

    private static final int DEFAULT_DIM = 64;

    private final int dim;

    public DeterministicEmbedder() {
        this(DEFAULT_DIM);
    }

    public DeterministicEmbedder(int dim) {
        this.dim = Math.max(8, dim);
    }

    public int dimension() {
        return dim;
    }

    @Override
    public float[] embed(String text) {
        float[] v = new float[dim];
        for (String token : Tokenizer.tokenize(text)) {
            int h = token.hashCode();
            int bucket = Math.floorMod(h, dim);
            v[bucket] += 1.0f;
            // A second, sign-carrying projection reduces bucket collisions.
            int bucket2 = Math.floorMod(h * 31 + 7, dim);
            v[bucket2] += (h & 1) == 0 ? 0.5f : -0.5f;
        }
        return Vectors.l2normalize(v);
    }
}
