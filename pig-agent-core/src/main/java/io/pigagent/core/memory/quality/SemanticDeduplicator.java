package io.pigagent.core.memory.quality;

import io.pigagent.core.memory.search.Embedder;
import io.pigagent.core.memory.search.Vectors;
import io.pigagent.core.search.Bm25Index;
import io.pigagent.core.search.SearchDocument;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Deterministic <b>semantic</b> deduplicator over fact units — capability
 * {@code memory-consolidation-quality}. Unlike a pure string-equality pass, it merges near-duplicates by
 * semantic similarity: two units above {@code threshold} are grouped and only one <b>representative</b>
 * (the longest text; ties broken by the later index, so the most informative version survives) is kept.
 * It is a confined, deterministic safety net after the native LLM consolidation — it drops, it never
 * rewrites text with a model.
 *
 * <p><b>Two similarity channels, reusing the shared retrieval primitives:</b>
 * <ul>
 *   <li><b>Vector cosine</b> when an {@link Embedder} is wired: {@code cosine(embed(a), embed(b))}
 *       ({@link Vectors#cosine}); the offline {@code DeterministicEmbedder} makes it testable without a
 *       live model, a real embedder (M-B, same {@code resolveEmbedder} seam) gives real semantics.</li>
 *   <li><b>BM25</b> when no embedder is wired (zero-dependency degradation): a self-normalized Okapi
 *       BM25 score over {@link Bm25Index} — {@code score_i(j) / score_i(i)}, symmetrized by
 *       {@code max(sim(i,j), sim(j,i))} — so identical/near-identical units score ~1.</li>
 * </ul>
 *
 * <p>Grouping is transitive (union-find over the above-threshold pair graph). Output is the surviving
 * indices in ascending order — so the curator can rebuild {@code MEMORY.md} by dropping only the
 * non-survivors, preserving structure. Pure, offline, no external dependency; never mutates its input.
 */
public final class SemanticDeduplicator {

    private final Embedder embedder; // nullable → BM25 channel
    private final double threshold;

    /**
     * @param embedder  the vector embedder, or {@code null} to use the BM25 similarity channel
     * @param threshold similarity in {@code [0,1]} at/above which two units are near-duplicates (clamped)
     */
    public SemanticDeduplicator(Embedder embedder, double threshold) {
        this.embedder = embedder;
        this.threshold = Math.min(1.0, Math.max(0.0, threshold));
    }

    /** Whether the real vector channel is active (an embedder is wired) vs BM25 degradation. */
    public boolean vectorEnabled() {
        return embedder != null;
    }

    /**
     * The indices (into {@code units}) of the units that survive dedup — one representative per
     * near-duplicate group — in ascending order. Empty/one-element input returns all indices.
     */
    public List<Integer> survivingIndices(List<String> units) {
        int n = units == null ? 0 : units.size();
        List<Integer> all = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            all.add(i);
        }
        if (n <= 1) {
            return all;
        }

        double[][] sims = pairwiseSimilarities(units);
        int[] parent = newUnionFind(n);
        for (int i = 0; i < n; i++) {
            for (int j = i + 1; j < n; j++) {
                if (sims[i][j] >= threshold) {
                    union(parent, i, j);
                }
            }
        }

        // Per group (root), pick the representative: longest text, tie → larger index.
        int[] rep = new int[n];
        java.util.Arrays.fill(rep, -1);
        for (int i = 0; i < n; i++) {
            int root = find(parent, i);
            int cur = rep[root];
            if (cur < 0 || betterRepresentative(units, i, cur)) {
                rep[root] = i;
            }
        }

        List<Integer> survivors = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            if (rep[find(parent, i)] == i) {
                survivors.add(i);
            }
        }
        return survivors; // already ascending (built over 0..n-1)
    }

    /**
     * The surviving unit texts in order — convenience over {@link #survivingIndices(List)} for callers
     * that don't need the index mapping.
     */
    public List<String> dedup(List<String> units) {
        List<String> out = new ArrayList<>();
        for (int idx : survivingIndices(units)) {
            out.add(units.get(idx));
        }
        return out;
    }

    /** True when candidate {@code i} is a better representative than {@code cur} (longer, tie → later). */
    private static boolean betterRepresentative(List<String> units, int i, int cur) {
        int li = units.get(i).length();
        int lc = units.get(cur).length();
        if (li != lc) {
            return li > lc;
        }
        return i > cur;
    }

    private double[][] pairwiseSimilarities(List<String> units) {
        return embedder != null ? cosineSimilarities(units) : bm25Similarities(units);
    }

    private double[][] cosineSimilarities(List<String> units) {
        int n = units.size();
        float[][] vecs = new float[n][];
        for (int i = 0; i < n; i++) {
            vecs[i] = embedder.embed(units.get(i));
        }
        double[][] sims = new double[n][n];
        for (int i = 0; i < n; i++) {
            for (int j = i + 1; j < n; j++) {
                double s = Vectors.cosine(vecs[i], vecs[j]);
                sims[i][j] = s;
                sims[j][i] = s;
            }
        }
        return sims;
    }

    private double[][] bm25Similarities(List<String> units) {
        int n = units.size();
        Bm25Index index = new Bm25Index();
        List<SearchDocument> docs = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            docs.add(new UnitDoc(i, units.get(i)));
        }
        index.index(docs);

        // scoresByUnit[i] = BM25 scores of every unit when querying with unit i's own text.
        List<Map<String, Double>> scoresByUnit = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            scoresByUnit.add(index.score(units.get(i)));
        }
        double[][] sims = new double[n][n];
        for (int i = 0; i < n; i++) {
            for (int j = i + 1; j < n; j++) {
                double s = Math.max(selfNormalized(scoresByUnit.get(i), i, j),
                        selfNormalized(scoresByUnit.get(j), j, i));
                sims[i][j] = s;
                sims[j][i] = s;
            }
        }
        return sims;
    }

    /** {@code score_from(to) / score_from(from)} — 1.0 for an identical token profile, 0 when undefined. */
    private static double selfNormalized(Map<String, Double> scoresFrom, int from, int to) {
        double self = scoresFrom.getOrDefault(id(from), 0.0);
        if (self <= 0.0) {
            return 0.0;
        }
        double other = scoresFrom.getOrDefault(id(to), 0.0);
        return Math.min(1.0, other / self);
    }

    private static int[] newUnionFind(int n) {
        int[] parent = new int[n];
        for (int i = 0; i < n; i++) {
            parent[i] = i;
        }
        return parent;
    }

    private static int find(int[] parent, int x) {
        while (parent[x] != x) {
            parent[x] = parent[parent[x]];
            x = parent[x];
        }
        return x;
    }

    private static void union(int[] parent, int a, int b) {
        int ra = find(parent, a);
        int rb = find(parent, b);
        if (ra != rb) {
            parent[Math.max(ra, rb)] = Math.min(ra, rb);
        }
    }

    private static String id(int i) {
        return "u" + i;
    }

    /** A fact unit projected onto the shared {@link SearchDocument} contract for BM25 indexing. */
    private record UnitDoc(int ordinal, String body) implements SearchDocument {
        @Override
        public String id() {
            return SemanticDeduplicator.id(ordinal);
        }

        @Override
        public String text() {
            return body;
        }
    }
}
