package io.pigagent.core.memory.search;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Pure hybrid ranker for memory search — capability {@code hybrid-memory-search}. Given per-document
 * BM25 keyword scores and vector cosine scores, it min-max-normalizes each component independently
 * (making the two different-scaled scores comparable), blends
 * {@code score = bm25Weight·BM25 + vectorWeight·cosine}, deduplicates by document id (a doc matched by
 * both paths appears once), filters by a minimum blended score, and returns the top-K.
 *
 * <p>Degrades cleanly: an empty vector map (no embedder) yields BM25-only ranking (order preserved by
 * min-max normalization); an empty BM25 map yields vector-only. Deterministic, no external dependency.
 */
public final class HybridRanker {

    private HybridRanker() {
    }

    /** A ranked document id with its blended score in {@code [0, 1]}. */
    public record Scored(String id, double score) {
    }

    /**
     * @param bm25Scores   per-doc BM25 scores (any positive scale); may be empty
     * @param vectorScores per-doc cosine scores (any scale); empty ⇒ BM25-only
     * @param bm25Weight   weight on the normalized BM25 component (default 0.7)
     * @param vectorWeight weight on the normalized vector component (default 0.3)
     * @param minScore     drop docs whose blended score is below this (0 ⇒ keep all matched)
     * @param topK         keep at most this many (≤0 ⇒ keep all)
     */
    public static List<Scored> rank(Map<String, Double> bm25Scores, Map<String, Double> vectorScores,
                                    double bm25Weight, double vectorWeight, double minScore, int topK) {
        Map<String, Double> nb = normalize(bm25Scores);
        Map<String, Double> nv = normalize(vectorScores);

        Set<String> ids = new LinkedHashSet<>();
        ids.addAll(bm25Scores.keySet());
        ids.addAll(vectorScores.keySet());

        List<Scored> ranked = new ArrayList<>(ids.size());
        for (String id : ids) {
            double blended = bm25Weight * nb.getOrDefault(id, 0.0)
                    + vectorWeight * nv.getOrDefault(id, 0.0);
            if (blended >= minScore) {
                ranked.add(new Scored(id, blended));
            }
        }
        ranked.sort((a, b) -> Double.compare(b.score(), a.score()));
        if (topK > 0 && ranked.size() > topK) {
            return new ArrayList<>(ranked.subList(0, topK));
        }
        return ranked;
    }

    /**
     * Min-max normalize scores to {@code [0, 1]}. Empty ⇒ empty; all-equal (incl. single entry) ⇒ every
     * value 1.0 (so a lone match is not zeroed).
     */
    static Map<String, Double> normalize(Map<String, Double> scores) {
        Map<String, Double> out = new java.util.HashMap<>();
        if (scores.isEmpty()) {
            return out;
        }
        double min = Double.POSITIVE_INFINITY;
        double max = Double.NEGATIVE_INFINITY;
        for (double v : scores.values()) {
            min = Math.min(min, v);
            max = Math.max(max, v);
        }
        double range = max - min;
        for (Map.Entry<String, Double> e : scores.entrySet()) {
            out.put(e.getKey(), range <= 0.0 ? 1.0 : (e.getValue() - min) / range);
        }
        return out;
    }
}
